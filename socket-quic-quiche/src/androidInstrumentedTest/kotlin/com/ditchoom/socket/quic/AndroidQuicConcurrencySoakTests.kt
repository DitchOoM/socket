package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Concurrency + soak tests on Android — the Android port of the JVM/Linux [QuicConcurrencySoakTestSuite]
 * (issue #87, suite #2). `androidInstrumentedTest` can't see `commonTest`, so this is a self-contained
 * parallel copy (same reason [AndroidQuicImpairmentTests] / [AndroidQuicPassiveMigrationTests] duplicate
 * their suites). The three test bodies + helpers mirror the shared suite exactly.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicConcurrencySoakTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
        )

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    @Test
    fun manyConcurrentStreamsOnOneConnectionRoundTrip() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                            val serverJob = launch(Dispatchers.IO) { connections { echoEveryStream() } }
                            try {
                                withQuicConnection("127.0.0.1", port, options, timeout = 15.seconds) {
                                    val results =
                                        (0 until CONCURRENT_STREAMS)
                                            .map { i ->
                                                async(Dispatchers.IO) {
                                                    val stream = openStream()
                                                    val echoed = stream.echoExact("stream-$i")
                                                    stream.close()
                                                    echoed
                                                }
                                            }.awaitAll()
                                    results.forEachIndexed { i, echoed ->
                                        assertEquals("stream-$i", echoed, "stream $i did not round-trip under concurrency")
                                    }
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
            }
        }

    // Mirror of QuicConcurrencySoakTestSuite.manyConcurrentStreamsHighConcurrencyRoundTrip (the shared
    // suite isn't visible from androidInstrumentedTest). ~3x the baseline stream fan-out on one connection.
    @Test
    fun manyConcurrentStreamsHighConcurrencyRoundTrip() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(30.seconds) {
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                            val serverJob = launch(Dispatchers.IO) { connections { echoEveryStream() } }
                            try {
                                withQuicConnection("127.0.0.1", port, options, timeout = 20.seconds) {
                                    val results =
                                        (0 until HIGH_CONCURRENT_STREAMS)
                                            .map { i ->
                                                async(Dispatchers.IO) {
                                                    val stream = openStream()
                                                    val echoed = stream.echoExact("hi-stream-$i")
                                                    stream.close()
                                                    echoed
                                                }
                                            }.awaitAll()
                                    results.forEachIndexed { i, echoed ->
                                        assertEquals("hi-stream-$i", echoed, "stream $i did not round-trip at high concurrency")
                                    }
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun manyConnectionsConcurrentlyRoundTrip() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                            val serverJob = launch(Dispatchers.IO) { connections { echoEveryStream() } }
                            try {
                                val results =
                                    (0 until CONCURRENT_CONNECTIONS)
                                        .map { i ->
                                            async(Dispatchers.IO) {
                                                withQuicConnection("127.0.0.1", port, options, timeout = 15.seconds) {
                                                    val stream = openStream()
                                                    val echoed = stream.echoExact("conn-$i")
                                                    stream.close()
                                                    echoed
                                                }
                                            }
                                        }.awaitAll()
                                results.forEachIndexed { i, echoed ->
                                    assertEquals("conn-$i", echoed, "connection $i did not round-trip under concurrency")
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
            }
        }

    // Mirror of QuicConcurrencySoakTestSuite.manyConnectionsHighConcurrencyRoundTrip. ~3x the baseline
    // simultaneous-connection fan-out. Android (quiche) supports concurrent connections to one endpoint.
    @Test
    fun manyConnectionsHighConcurrencyRoundTrip() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(35.seconds) {
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                            val serverJob = launch(Dispatchers.IO) { connections { echoEveryStream() } }
                            try {
                                val results =
                                    (0 until HIGH_CONCURRENT_CONNECTIONS)
                                        .map { i ->
                                            async(Dispatchers.IO) {
                                                withQuicConnection("127.0.0.1", port, options, timeout = 25.seconds) {
                                                    val stream = openStream()
                                                    val echoed = stream.echoExact("hi-conn-$i")
                                                    stream.close()
                                                    echoed
                                                }
                                            }
                                        }.awaitAll()
                                results.forEachIndexed { i, echoed ->
                                    assertEquals("hi-conn-$i", echoed, "connection $i did not round-trip at high concurrency")
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun sustainedEchoLoopHasNoPerOperationLeak() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    val tracking = TrackingBufferFactory()
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                            val serverJob = launch(Dispatchers.IO) { connections { echoEveryStream() } }
                            try {
                                withQuicConnection(
                                    "127.0.0.1",
                                    port,
                                    options,
                                    TransportConfig(bufferFactory = tracking),
                                    timeout = 15.seconds,
                                ) {
                                    val stream = openStream()
                                    for (round in 0 until SOAK_ROUNDS) {
                                        assertEquals(
                                            "round-$round",
                                            stream.echoExact("round-$round"),
                                            "soak round $round did not round-trip",
                                        )
                                    }
                                    stream.close()
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                    val live = tracking.liveCount
                    assertTrue(
                        live <= MAX_RESIDUAL_BUFFERS,
                        "soak left $live live client buffers (> $MAX_RESIDUAL_BUFFERS over $SOAK_ROUNDS rounds) — a per-operation buffer leak",
                    )
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun QuicScope.echoEveryStream() {
        streams().collect { stream ->
            launch {
                try {
                    while (true) {
                        val data = stream.read(15.seconds)
                        if (data is ReadResult.Data) {
                            stream.write(data.buffer, 10.seconds)
                        } else {
                            break
                        }
                    }
                } finally {
                    stream.close()
                }
            }
        }
    }

    private suspend fun QuicByteStream.echoExact(payload: String): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 10.seconds)
        return readExactly(payload.length, 10.seconds)
    }

    private suspend fun QuicByteStream.readExactly(
        total: Int,
        timeout: Duration,
    ): String {
        val sb = StringBuilder(total)
        while (sb.length < total) {
            val r = read(timeout)
            if (r is ReadResult.Data) {
                sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                r.buffer.freeIfNeeded()
            } else {
                break
            }
        }
        return sb.toString()
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    private companion object {
        private const val CONCURRENT_STREAMS = 20
        private const val CONCURRENT_CONNECTIONS = 8

        // Higher-concurrency variants (~3x baseline); mirror the shared QuicConcurrencySoakTestSuite.
        private const val HIGH_CONCURRENT_STREAMS = 64
        private const val HIGH_CONCURRENT_CONNECTIONS = 24
        private const val SOAK_ROUNDS = 128
        private const val MAX_RESIDUAL_BUFFERS = 80
    }
}

/**
 * Self-contained copy of commonTest's `TrackingBufferFactory` (androidInstrumentedTest can't see
 * commonTest). Tracks allocations and frees so the soak test can read [liveCount].
 */
private class TrackingBufferFactory(
    private val delegate: BufferFactory = BufferFactory.deterministic(),
) : BufferFactory {
    private val allocatedCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val freedCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    val liveCount: Int get() = allocatedCount.get() - freedCount.get()

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = delegate.allocate(size, byteOrder)
        allocatedCount.incrementAndGet()
        return TrackingPlatformBuffer(buffer) { freedCount.incrementAndGet() }
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)
}

private class TrackingPlatformBuffer(
    private val delegate: PlatformBuffer,
    private val onFree: () -> Unit,
) : PlatformBuffer by delegate,
    CloseableBuffer {
    private var freed = false

    override val isFreed: Boolean get() = freed

    override fun freeNativeMemory() {
        if (freed) return // idempotent
        freed = true
        onFree()
        delegate.freeNativeMemory()
    }
}
