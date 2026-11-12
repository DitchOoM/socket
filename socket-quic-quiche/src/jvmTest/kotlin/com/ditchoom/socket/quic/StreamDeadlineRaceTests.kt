package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.BytesWritten
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A deadline that fires at the instant the bounded block returns: the caller gets exactly one of the value
 * and the timeout, and what the block took ownership of is not lost.
 *
 * The instant is made exact, not hoped for: a [StreamDeadlineScheduling] whose handle fires the expiry from
 * `dispose()`, which [withStreamDeadline] calls when the block has returned and before the scope completes,
 * so the expiry always lands in the window between the two.
 */
class StreamDeadlineRaceTests {
    /** Arms nothing; when the bound is disarmed (the block has returned) the expiry fires first. */
    private val firesWhenTheBlockReturns =
        StreamDeadlineScheduling(
            StreamDeadlineScheduler { _, action -> DisposableHandle { action.run() } },
        )

    @Test
    fun aValueAndAnExpiryAtTheSameInstantYieldTheTimeoutNotBoth(): Unit =
        runBlocking {
            val delivered = AtomicInteger()
            assertFailsWith<TimeoutCancellationException> {
                withContext(firesWhenTheBlockReturns) {
                    withStreamDeadline(30.seconds) { delivered.incrementAndGet() }
                }
            }
            assertEquals(1, delivered.get())
        }

    @Test
    fun aBlockThatRecordedWhatItTookRecoversItFromTheTimeoutAndNothingIsLostOrDuplicated(): Unit =
        runBlocking {
            val taken = AtomicInteger()
            val handedOver = AtomicInteger()
            repeat(300) {
                var recorded: Int? = null
                val result =
                    try {
                        withStreamDeadline(3.milliseconds) {
                            delay(Random.nextLong(1, 5))
                            taken.incrementAndGet()
                            recorded = 1
                            1
                        }
                    } catch (e: TimeoutCancellationException) {
                        recorded ?: 0 // timed out before taking anything: nothing to hand over
                    }
                handedOver.addAndGet(result)
            }
            // Each iteration either took and handed over one, or timed out before taking: no more, none lost.
            assertEquals(taken.get(), handedOver.get())
        }

    @Test
    fun aWriteWhoseBytesQuicheAcceptedIsReportedWhenTheDeadlineLandsAfterTheBlockReturned(): Unit =
        withLoopback { client ->
            val stream = client.openStream()
            val buffer = BufferFactory.Default.allocate(64)
            repeat(64) { buffer.writeByte(1) }
            buffer.resetForRead()
            val written =
                withContext(firesWhenTheBlockReturns) {
                    stream.write(buffer, 30.seconds)
                }
            assertEquals(BytesWritten(64), written, "bytes on the stream were reported as a timeout")
            stream.shutdownSend()
            stream.close()
        }

    @Test
    fun aReadWhoseChunkWasTakenIsDeliveredWhenTheDeadlineLandsAfterTheBlockReturned(): Unit =
        withLoopback { client ->
            val stream = client.openStream()
            val buffer = BufferFactory.Default.allocate(64)
            repeat(64) { buffer.writeByte(1) }
            buffer.resetForRead()
            stream.write(buffer, 30.seconds)
            val read = withContext(firesWhenTheBlockReturns) { stream.read(30.seconds) { it.remaining() } }
            assertIs<ScopedRead.Data<*>>(read, "the chunk the read took was reported as a timeout")
            assertEquals(64, read.value)
            stream.shutdownSend()
            stream.close()
        }

    private fun withLoopback(body: suspend (QuicScope) -> Unit): Unit =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(StreamDeadlineRaceTests::class) {
                withTimeout(60.seconds) {
                    val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 20.seconds)
                    val url = this::class.java.classLoader.getResource("certs/cert.crt") ?: error("Test cert not found")
                    val dir = java.io.File(url.toURI()).parentFile
                    val tls =
                        QuicTlsConfig(
                            certChainPath = java.io.File(dir, "cert.crt").absolutePath,
                            privKeyPath = java.io.File(dir, "cert.key").absolutePath,
                        )
                    withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        val echoed = stream.read(30.seconds) { chunk -> stream.write(chunk, 30.seconds) }
                                        if (echoed !is ScopedRead.Data) break
                                    }
                                    stream.close()
                                }
                            }
                        try {
                            withQuicConnection("localhost", port, options, timeout = 20.seconds) { body(this) }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
