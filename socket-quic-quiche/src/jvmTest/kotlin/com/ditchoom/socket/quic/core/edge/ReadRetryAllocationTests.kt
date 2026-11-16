package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Nobody
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a read allocates each time quiche wakes it and it finds nothing yet: a retry. How many retries a read gets
 * depends on the host's speed, so a retry that allocates makes the loop's bytes per echo depend on it too.
 */
class ReadRetryAllocationTests {
    @Test
    fun aParkedReadThatFindsNothingYetAllocatesNothingOnEachRetry() =
        runBlocking {
            skipOnMissingNativeLib(ReadRetryAllocationTests::class) {
                withEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val stream = client.connection.openStream()
                    val parked = async(Dispatchers.Default) { runCatching { stream.read(60.seconds) } }
                    var perRetry = Double.NaN
                    val give = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (perRetry.isNaN() && System.nanoTime() < give) {
                        val probe = Probe(stream.streamId)
                        client.conn.submit(probe, CallerPlace.HandToLoop)
                        perRetry = probe.bytesPerRetry.get(30, TimeUnit.SECONDS)
                        if (perRetry.isNaN()) Thread.sleep(1)
                    }
                    parked.cancel()
                    client.connection.close(QuicError.NoError)
                    println("[core read retry allocation] %.2f B per retry on the loop".format(perRetry))
                    assertTrue(perRetry < 1.0, "a retry of a parked read allocated %.2f B on the loop".format(perRetry))
                }
            }
        }

    /** Runs on the loop: once a read is parked on [stream], wakes it [MEASURED] times and answers the bytes per wake; NaN if none is parked yet. */
    private class Probe(
        private val stream: QuicStreamId,
    ) : ConnWork() {
        val bytesPerRetry = CompletableFuture<Double>()

        override fun run(inside: Inside) {
            val entry = inside.streams.entry(stream)
            if (entry.reader === Nobody) return void()
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val id = Thread.currentThread().id
            repeat(WARM_UP) { entry.wakeReader(inside.streams) }
            val before = threads.getThreadAllocatedBytes(id)
            repeat(MEASURED) { entry.wakeReader(inside.streams) }
            bytesPerRetry.complete((threads.getThreadAllocatedBytes(id) - before).toDouble() / MEASURED)
        }

        private fun void() {
            bytesPerRetry.complete(Double.NaN)
        }

        override fun gone(end: ConnEnd) {
            bytesPerRetry.complete(Double.NaN)
        }
    }

    private companion object {
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
    }
}
