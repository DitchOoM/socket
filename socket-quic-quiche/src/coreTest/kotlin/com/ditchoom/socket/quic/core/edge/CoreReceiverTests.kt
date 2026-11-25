package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamClosedException
import com.ditchoom.socket.quic.QuicStreamReceiver
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamReceive
import com.ditchoom.socket.quic.StreamRoom
import com.ditchoom.socket.quic.StreamSend
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.readEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * A stream's receiver, called without suspension on the connection's handler lane, and the non-suspending send it
 * replies with, over a real loopback socket on every platform.
 */
class CoreReceiverTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aReceiverGetsEveryByteInOrderThenTheEnd() =
        coreTest("CoreReceiverTests.aReceiverGetsEveryByteInOrderThenTheEnd") {
            withCoreServer(onConnection = { core -> core.public().handleStreams(::echo) }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                val collected = Collected()
                assertSame(StreamReceive.Receiving, receive.receive(collected))
                assertIs<StreamWrite.Written>(send.writeAndFinish(bytes(big())))
                assertSame<ScopedRead.End>(ScopedRead.End.PeerFinished, withTimeout(WAIT) { collected.end.await() })
                assertContentEquals(big(), collected.bytes())
                client.close()
            }
        }

    @Test
    fun aReceiverRepliesWithTrySendWithoutSuspending() =
        coreTest("CoreReceiverTests.aReceiverRepliesWithTrySendWithoutSuspending") {
            val refused = CompletableDeferred<StreamSend>()
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    val stream = incoming as QuicStream
                    val ended = CompletableDeferred<ScopedRead.End>()
                    val echo =
                        object : QuicStreamReceiver {
                            override fun onBytes(bytes: ReadBuffer) {
                                when (val sent = stream.send.trySend(bytes)) {
                                    is StreamSend.Sent -> Unit
                                    else -> refused.complete(sent)
                                }
                            }

                            override fun onEnd(end: ScopedRead.End) {
                                stream.send.tryFinish()
                                ended.complete(end)
                            }
                        }
                    stream.receive.receive(echo)
                    ended.await()
                }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                repeat(ROUNDS) { round ->
                    val message = ByteArray(MESSAGE) { (it + round).toByte() }
                    assertIs<StreamWrite.Written>(send.write(bytes(message)))
                    val got = Collected.read(receive, MESSAGE)
                    assertContentEquals(message, got, "round $round")
                }
                assertIs<StreamWrite.Written>(send.finish())
                assertSame<ScopedRead.NoData>(ScopedRead.End.PeerFinished, withTimeout(WAIT) { receive.readEach { } })
                assertEquals(false, refused.isCompleted, "every reply fit: ${if (refused.isCompleted) refused.await() else ""}")
                client.close()
            }
        }

    @Test
    fun aReplyLargerThanTheSendQueueIsTakenInPartsAndArrivesWhole() =
        coreTest("CoreReceiverTests.aReplyLargerThanTheSendQueueIsTakenInPartsAndArrivesWhole") {
            val partial = CompletableDeferred<Unit>()
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    val stream = incoming as QuicStream
                    val ended = CompletableDeferred<ScopedRead.End>()
                    // The reply is the bytes themselves: trySend takes what the send queue has room for, and the
                    // receiver leaves the rest, which is offered again once the queue made room.
                    val echo =
                        object : QuicStreamReceiver {
                            override fun onBytes(bytes: ReadBuffer) {
                                stream.send.trySend(bytes)
                                if (bytes.remaining() > 0) partial.complete(Unit)
                            }

                            override fun onEnd(end: ScopedRead.End) {
                                stream.send.tryFinish()
                                ended.complete(end)
                            }
                        }
                    stream.receive.receive(echo)
                    ended.await()
                }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                coroutineScope {
                    launch {
                        val chunk = BufferFactory.network().allocate(LARGE_CHUNK)
                        var at = 0
                        while (at < LARGE) {
                            chunk.resetForWrite()
                            repeat(LARGE_CHUNK) { chunk.writeByte(((at + it) % PATTERN).toByte()) }
                            chunk.resetForRead()
                            assertIs<StreamWrite.Written>(send.write(chunk))
                            at += LARGE_CHUNK
                        }
                        assertIs<StreamWrite.Written>(send.finish())
                    }
                    var got = 0
                    val end =
                        withTimeout(LONG_WAIT) {
                            receive.readEach { bytes ->
                                while (bytes.remaining() > 0) {
                                    assertEquals((got % PATTERN).toByte(), bytes.readByte(), "byte $got")
                                    got++
                                }
                            }
                        }
                    assertSame<ScopedRead.NoData>(ScopedRead.End.PeerFinished, end)
                    assertEquals(LARGE, got)
                }
                assertEquals(true, partial.isCompleted, "the receiver left part of a call for the next")
                client.close()
            }
        }

    @Test
    fun aStreamHasOneReaderAReceiverOrReads() =
        coreTest("CoreReceiverTests.aStreamHasOneReaderAReceiverOrReads") {
            withCoreServer(onConnection = { core -> core.public().handleStreams(::echo) }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                val collected = Collected()
                assertSame(StreamReceive.Receiving, receive.receive(collected))
                assertSame(StreamReceive.NotReceiving.AlreadyReading, receive.receive(Collected()))
                assertFailsWith<QuicStreamClosedException> { receive.readEach { } }
                send.finish()
                withTimeout(WAIT) { collected.end.await() }
                client.close()
            }
        }

    @Test
    fun aReceiverHearsTheConnectionsEnd() =
        coreTest("CoreReceiverTests.aReceiverHearsTheConnectionsEnd") {
            withCoreServer(onConnection = { core ->
                val connection = core.public()
                connection.handleStreams { connection.close(QuicCloseChoice.Immediate(QuicError.ApplicationError(CLOSE_CODE))) }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                val collected = Collected()
                receive.receive(collected)
                send.write(bytes(ByteArray(MESSAGE)))
                val end = assertIs<ScopedRead.End.ConnectionEnded>(withTimeout(WAIT) { collected.end.await() })
                assertIs<QuicConnectionEnd.ByPeer>(end.end)
                assertIs<StreamSend.NotSent.ConnectionEnded>(send.trySend(bytes(ByteArray(MESSAGE))))
            }
        }

    @Test
    fun aSendThePeerStoppedEndsAsAValue() =
        coreTest("CoreReceiverTests.aSendThePeerStoppedEndsAsAValue") {
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    val stream = incoming as QuicStream
                    stream.receive.stop(QuicAppErrorCode(STOP_CODE))
                    stream.send.finish()
                }
            }) { server ->
                val client = connect(server.port)
                val (send, _) = client.openStream().orFail()
                val chunk = ByteArray(MESSAGE)
                val stopped =
                    withTimeout(WAIT) {
                        while (true) {
                            when (val sent = send.trySend(bytes(chunk))) {
                                is StreamSend.Sent -> kotlinx.coroutines.delay(10)
                                is StreamSend.NotSent.WindowFull ->
                                    when (val room = sent.awaitRoom()) {
                                        is StreamRoom.Over -> return@withTimeout room.why
                                        else -> Unit
                                    }
                                is StreamSend.NotSent.Over -> return@withTimeout sent
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        StreamSend.NotSent.Finished
                    }
                assertEquals(STOP_CODE, assertIs<StreamSend.NotSent.PeerStopped>(stopped).code.value)
                assertSame(stopped, send.trySend(bytes(chunk)), "the first failure stays")
                client.close()
            }
        }

    private suspend fun connect(port: Int): QuicConnection = connectCore(port, loops = loops).connection.public()

    private fun <S> com.ditchoom.socket.quic.StreamOpen<S>.orFail(): S = getOrElse { throw AssertionError("no stream was opened: $it") }

    private suspend fun echo(incoming: QuicIncomingStream) {
        when (incoming) {
            is QuicStream -> incoming.receive.readEach { incoming.send.write(it) }
            is QuicReceiveStream -> incoming.readEach { }
        }
    }

    /** A receiver that keeps a copy of everything it is lent, and completes [end] with the end. */
    private class Collected : QuicStreamReceiver {
        private val got = ArrayList<Byte>()
        val end = CompletableDeferred<ScopedRead.End>()

        override fun onBytes(bytes: ReadBuffer) {
            while (bytes.remaining() > 0) got.add(bytes.readByte())
        }

        override fun onEnd(end: ScopedRead.End) {
            this.end.complete(end)
        }

        fun bytes(): ByteArray = got.toByteArray()

        companion object {
            /** Reads [length] bytes with suspend reads. */
            suspend fun read(
                receive: QuicReceiveStream,
                length: Int,
            ): ByteArray {
                val out = ArrayList<Byte>()
                while (out.size < length) {
                    receive
                        .read { while (it.remaining() > 0) out.add(it.readByte()) }
                        .getOrElse { throw AssertionError("the echo ended after ${out.size} B: $it") }
                }
                return out.toByteArray()
            }
        }
    }

    private fun bytes(content: ByteArray): ReadBuffer =
        BufferFactory.network().allocate(content.size).apply {
            writeBytes(content)
            resetForRead()
        }

    private fun big() = ByteArray(BIG) { (it * 31).toByte() }

    private companion object {
        const val BIG = 1_048_576
        const val MESSAGE = 1_000
        const val LARGE = 4 * 1_048_576
        const val LARGE_CHUNK = 16_384
        const val PATTERN = 251
        const val ROUNDS = 100
        const val STOP_CODE = 0x42L
        const val CLOSE_CODE = 0x77L
        val WAIT = 10.seconds
        val LONG_WAIT = 60.seconds
    }
}
