package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamReadError
import com.ditchoom.socket.quic.QuicStreamReadException
import com.ditchoom.socket.quic.QuicStreamReceiver
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamReceive
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.readEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The suspend read, layered on the receiver's lent buffer: a deadline that passes, a cancelled read, a second read while
 * one is out, and a read that hands the stream to a receiver part way, each losing no byte, on every platform.
 */
class CoreReadInboxTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** The server echoes what it is told to, once the client asks: each line of [gate] lets one message go. */
    private suspend fun echoOnCue(
        incoming: QuicIncomingStream,
        cue: Channel<Unit>,
    ) {
        val stream = incoming as QuicStream
        stream.receive.readEach { bytes ->
            cue.receive()
            stream.send.write(bytes)
        }
        stream.send.finish()
    }

    @Test
    fun aReadWhoseDeadlinePassesLosesNothingOfWhatComesAfter() =
        coreTest("CoreReadInboxTests.aReadWhoseDeadlinePassesLosesNothingOfWhatComesAfter") {
            val cue = Channel<Unit>(Channel.UNLIMITED)
            withCoreServer(onConnection = { core -> core.public().handleStreams { echoOnCue(it, cue) } }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                assertIs<StreamWrite.Written>(send.write(bytes(message(0))))
                assertSame<Any>(
                    ScopedRead.DeadlinePassed,
                    receive.read(ReadPolicy.Bounded(SHORT)) { fail("no bytes yet") }.fold({ it }, { it }),
                )
                cue.send(Unit)
                assertContentEquals(message(0), readExactly(receive, MESSAGE))
                send.finish()
                assertSame<ScopedRead.NoData>(ScopedRead.End.PeerFinished, receive.readEach { })
                client.close()
            }
        }

    @Test
    fun aCancelledReadLosesNoBytes() =
        coreTest("CoreReadInboxTests.aCancelledReadLosesNoBytes") {
            val cue = Channel<Unit>(Channel.UNLIMITED)
            withCoreServer(onConnection = { core -> core.public().handleStreams { echoOnCue(it, cue) } }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                repeat(ROUNDS) { round ->
                    assertIs<StreamWrite.Written>(send.write(bytes(message(round))))
                    coroutineScope {
                        val parked = launch { receive.read { fail("a cancelled read ran its block") } }
                        delay(1)
                        parked.cancel()
                    }
                    cue.send(Unit)
                    assertContentEquals(message(round), readExactly(receive, MESSAGE), "round $round")
                }
                send.finish()
                assertSame<ScopedRead.NoData>(ScopedRead.End.PeerFinished, receive.readEach { })
                client.close()
            }
        }

    @Test
    fun aSecondReadWhileOneIsOutIsRefusedAndTheFirstGetsTheBytes() =
        coreTest("CoreReadInboxTests.aSecondReadWhileOneIsOutIsRefusedAndTheFirstGetsTheBytes") {
            val cue = Channel<Unit>(Channel.UNLIMITED)
            withCoreServer(onConnection = { core -> core.public().handleStreams { echoOnCue(it, cue) } }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                assertIs<StreamWrite.Written>(send.write(bytes(message(0))))
                coroutineScope {
                    val first = async(start = CoroutineStart.UNDISPATCHED) { readExactly(receive, MESSAGE) }
                    val refused = assertFailsWith<QuicStreamReadException> { receive.read { } }
                    assertSame<QuicStreamReadError>(QuicStreamReadError.ReadInProgress, refused.error)
                    cue.send(Unit)
                    assertContentEquals(message(0), withTimeout(WAIT) { first.await() })
                }
                client.close()
            }
        }

    @Test
    fun aStreamReadThenHandedToAReceiverLosesNoBytes() =
        coreTest("CoreReadInboxTests.aStreamReadThenHandedToAReceiverLosesNoBytes") {
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    val stream = incoming as QuicStream
                    stream.receive.readEach { stream.send.write(it) }
                    stream.send.finish()
                }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                assertIs<StreamWrite.Written>(send.write(bytes(message(0))))
                assertContentEquals(message(0), readExactly(receive, MESSAGE))
                val rest = Collect()
                assertSame(StreamReceive.Receiving, receive.receive(rest))
                repeat(ROUNDS) { assertIs<StreamWrite.Written>(send.write(bytes(message(it + 1)))) }
                send.finish()
                assertSame<ScopedRead.End>(ScopedRead.End.PeerFinished, withTimeout(WAIT) { rest.end.await() })
                val expected = (1..ROUNDS).flatMap { message(it).asList() }.toByteArray()
                assertContentEquals(expected, rest.bytes())
                client.close()
            }
        }

    @Test
    fun readsAfterTheEndAnswerTheEndAgain() =
        coreTest("CoreReadInboxTests.readsAfterTheEndAnswerTheEndAgain") {
            withCoreServer(onConnection = { core ->
                core.public().handleStreams { incoming ->
                    val stream = incoming as QuicStream
                    stream.receive.readEach { stream.send.write(it) }
                    stream.send.finish()
                }
            }) { server ->
                val client = connect(server.port)
                val (send, receive) = client.openStream().orFail()
                assertIs<StreamWrite.Written>(send.writeAndFinish(bytes(message(0))))
                assertContentEquals(message(0), readExactly(receive, MESSAGE))
                repeat(
                    3,
                ) { assertSame<Any>(ScopedRead.End.PeerFinished, receive.read { fail("bytes after the end") }.fold({ it }, { it })) }
                client.close()
            }
        }

    private suspend fun connect(port: Int): QuicConnection = connectCore(port, loops = loops).connection.public()

    private fun <S> com.ditchoom.socket.quic.StreamOpen<S>.orFail(): S = getOrElse { throw AssertionError("no stream was opened: $it") }

    private class Collect : QuicStreamReceiver {
        private val got = ArrayList<Byte>()
        val end = CompletableDeferred<ScopedRead.End>()

        override fun onBytes(bytes: ReadBuffer) {
            while (bytes.remaining() > 0) got.add(bytes.readByte())
        }

        override fun onEnd(end: ScopedRead.End) {
            this.end.complete(end)
        }

        fun bytes(): ByteArray = got.toByteArray()
    }

    private suspend fun readExactly(
        receive: QuicReceiveStream,
        length: Int,
    ): ByteArray {
        val out = ArrayList<Byte>()
        while (out.size < length) {
            receive
                .read { while (it.remaining() > 0) out.add(it.readByte()) }
                .getOrElse { throw AssertionError("the stream ended after ${out.size} B: $it") }
        }
        assertEquals(length, out.size)
        return out.toByteArray()
    }

    private fun fail(why: String): Nothing = throw AssertionError(why)

    private fun message(round: Int) = ByteArray(MESSAGE) { (it * 7 + round).toByte() }

    private fun bytes(content: ByteArray): ReadBuffer =
        BufferFactory.network().allocate(content.size).apply {
            writeBytes(content)
            resetForRead()
        }

    private companion object {
        const val MESSAGE = 1_000
        const val ROUNDS = 50
        val SHORT = 50.milliseconds
        val WAIT = 10.seconds
    }
}
