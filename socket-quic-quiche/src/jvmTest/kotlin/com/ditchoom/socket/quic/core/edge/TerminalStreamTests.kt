package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.withCoreStreamServer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A stream reset either way lets its entry go once both directions ended and nothing waits on it: the peer resetting
 * it after reading our side to its end, and our reset of it. A connection
 * whose streams end those ways keeps only its live streams, however many it opened.
 */
class TerminalStreamTests {
    @Test
    fun streamsThePeerResetsLeaveTheTable() =
        withServer(handle = { stream ->
            // An HTTP/3-style cancel of the response: the request is read to its end, the answer is reset.
            while (stream.read(10.seconds) { } is ScopedReadResult.Data<*>) Unit
            (stream as Resettable).reset(PEER_CODE)
        }) { client ->
            repeat(STREAMS) {
                val stream = client.connection.openStream()
                stream.write(payload(), 10.seconds)
                (stream as HalfCloseable).shutdownSend()
                val got = stream.read(10.seconds)
                got.release()
                assertTrue(got === ReadResult.Reset, "the peer's reset reached the reader: $got")
            }
            assertLiveAtMost(client)
        }

    @Test
    fun streamsWeResetLeaveTheTable() =
        withServer(handle = ::echo) { client ->
            repeat(STREAMS) {
                val stream = client.connection.openStream()
                stream.write(payload(), 10.seconds)
                stream.read(10.seconds).release()
                (stream as Resettable).reset(OUR_CODE)
            }
            assertLiveAtMost(client)
        }

    @Test
    fun streamsClosedWhileThePeerStillWritesDropWhatArrivesAndLeaveTheTable() {
        val failures =
            java.util.concurrent.atomic
                .AtomicInteger()
        val finished =
            java.util.concurrent.atomic
                .AtomicInteger()
        withServer(handle = { stream ->
            try {
                stream.read(10.seconds) { }
                repeat(PEER_WRITES) { stream.write(payload(), 10.seconds) }
                stream.close()
                finished.incrementAndGet()
            } catch (failure: Exception) {
                failures.incrementAndGet()
                throw failure
            }
        }) { client ->
            repeat(STREAMS) {
                val stream = client.connection.openStream()
                stream.write(payload(), 10.seconds)
                (stream as HalfCloseable).shutdownSend()
                // Closed with the peer's answer unread, while the peer is still writing it.
                stream.close()
            }
            assertLiveAtMost(client)
            val giveUp = System.nanoTime() + 10.seconds.inWholeNanoseconds
            while (finished.get() + failures.get() < STREAMS && System.nanoTime() < giveUp) Thread.sleep(20)
            assertTrue(
                failures.get() == 0 && finished.get() == STREAMS,
                "peer writes: ${finished.get()} finished, ${failures.get()} failed of $STREAMS",
            )
        }
    }

    private fun assertLiveAtMost(client: CoreClient) {
        // The peer's frames for the last streams may still be in flight: wait until the count settles under the bound.
        val giveUp = System.nanoTime() + 10.seconds.inWholeNanoseconds
        var live: Int
        do {
            val probe = LiveProbe()
            client.conn.submit(probe, CallerPlace.HandToLoop)
            live = probe.count.get(10, TimeUnit.SECONDS)
            if (live <= LIVE_AT_MOST) break
            Thread.sleep(20)
        } while (System.nanoTime() < giveUp)
        assertTrue(live <= LIVE_AT_MOST, "$live of $STREAMS ended streams still hold an entry")
    }

    private fun ReadResult.release() {
        if (this is ReadResult.Data) buffer.freeIfNeeded()
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
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

    private fun withServer(
        handle: suspend (QuicByteStream) -> Unit,
        body: suspend CoroutineScope.(CoreClient) -> Unit,
    ) = runBlocking {
        skipOnMissingNativeLib(TerminalStreamTests::class) {
            withCoreStreamServer(onStream = { stream -> runCatching { handle(stream) } }) { server ->
                val client = connectCore(server.port, factory = BufferFactory.network())
                body(client)
                client.connection.close(QuicError.NoError)
            }
        }
    }

    private companion object {
        const val STREAMS = 40
        const val LIVE_AT_MOST = 2
        const val PAYLOAD = 32
        const val PEER_CODE = 7L
        const val OUR_CODE = 9L
        const val PEER_WRITES = 64

        suspend fun echo(stream: QuicByteStream) {
            while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedReadResult.Data<*>) Unit
            stream.close()
        }
    }
}
