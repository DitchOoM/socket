package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A stream whose two sides both finished is spent: the connection lets its entry go, so its stream table holds the live
 * streams only and does not grow (and allocate its growth on whichever thread opens the next stream) with every stream a
 * connection ever opened. A read of a spent stream still answers its end.
 */
class SpentStreamTests {
    @Test
    fun streamsFinishedBothWaysLeaveTheTableAndAReadAfterTheEndStillAnswersIt() =
        runBlocking {
            skipOnMissingNativeLib(SpentStreamTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val payload = BufferFactory.network().allocate(PAYLOAD).apply { repeat(PAYLOAD) { writeByte(1) } }
                    repeat(STREAMS) {
                        val stream = client.connection.openStream()
                        payload.resetForRead()
                        stream.write(payload, 10.seconds)
                        val back = stream.read(10.seconds)
                        if (back !is ReadResult.Data) fail("no echo: $back")
                        back.buffer.freeIfNeeded()
                        (stream as HalfCloseable).shutdownSend()
                        if (stream.read(10.seconds) !== ReadResult.End) fail("the echo did not end")
                        assertSame(ReadResult.End, stream.read(10.seconds), "a read after the end")
                    }
                    val live = LiveProbe()
                    client.conn.submit(live, CallerPlace.HandToLoop)
                    val count = live.count.get(10, TimeUnit.SECONDS)
                    assertTrue(count <= 1, "$count of $STREAMS finished streams still hold an entry")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /**
     * The public shape: `writeAndFinish`, then scoped reads to the peer's FIN. A read that parked once and later met the
     * FIN while parked again is spent too.
     */
    @Test
    fun publicStreamsWrittenAndFinishedThenReadToTheirEndLeaveTheTable() =
        runBlocking {
            skipOnMissingNativeLib(SpentStreamTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val connection = client.connection.public()
                    val payload = BufferFactory.network().allocate(PAYLOAD).apply { repeat(PAYLOAD) { writeByte(1) } }
                    repeat(STREAMS) {
                        val (send, receive) = connection.openStream().getOrElse { fail("not opened: $it") }
                        payload.resetForRead()
                        if (send.writeAndFinish(payload) !== StreamWrite.Written) fail("the request was not written")
                        while (true) {
                            when (val read = receive.read { it.position(it.limit()) }.fold({ Unit }, { it })) {
                                is ScopedRead.End.PeerFinished -> break
                                is Unit -> Unit
                                else -> fail("the echo ended with $read")
                            }
                        }
                    }
                    val live = LiveProbe()
                    client.conn.submit(live, CallerPlace.HandToLoop)
                    val count = live.count.get(10, TimeUnit.SECONDS)
                    assertTrue(count <= 1, "$count of $STREAMS finished streams still hold an entry")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private class LiveProbe : ConnWork() {
        val count = CompletableFuture<Int>()

        override fun run(inside: Inside) {
            count.complete(inside.streams.liveStreams())
        }

        override fun gone(end: ConnEnd) {
            count.complete(-1)
        }
    }

    private companion object {
        const val STREAMS = 64
        const val PAYLOAD = 32
    }
}
