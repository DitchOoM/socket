package com.ditchoom.socket.benchmark.android

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QUIC over loopback inside the (R8-minified) test process. Each measured iteration is one complete
 * exchange in its own runBlocking, as in benchmarks/jvm. Microbenchmark reports min/median/max per
 * benchmark in its JSON; the median is the number to compare.
 */
@RunWith(AndroidJUnit4::class)
class QuicBenchmarks {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var loopback: AndroidQuicLoopback

    @Before
    fun setUp() {
        // SPIKE (perf/owned-loop-spike): -e quicSpikeLoop shared|one|group|limited1, set before any QUIC
        // class loads (OwnedLoopSpike reads it once).
        val loop = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("quicSpikeLoop") ?: "shared"
        System.setProperty("quic.spike.loop", loop)
        val setup = "# setup: loop=$loop cpus=${Runtime.getRuntime().availableProcessors()} " +
            "pool.size=${System.getProperty("kotlinx.coroutines.scheduler.core.pool.size") ?: "default"}"
        println(setup)
        android.util.Log.i("QuicSpike", setup)
        assertJniBackend()
        loopback = AndroidQuicLoopback()
    }

    @After
    fun tearDown() {
        // A setUp that failed (say, quiche did not load) must report its own error, not this one.
        if (::loopback.isInitialized) loopback.close()
    }

    /** One long-lived stream, 64 bytes each way: steady-state request/response latency. */
    @Test
    fun longLivedStreamRoundTrip64() = longLivedRoundTrip(64)

    /** The same with 1 KiB each way. */
    @Test
    fun longLivedStreamRoundTrip1024() = longLivedRoundTrip(1024)

    /** A new stream per request: open, 64 bytes, FIN, read the echo to the server's FIN, close. */
    @Test
    fun streamPerRequest() {
        val request = filled(64, echo = true)
        try {
            benchmarkRule.measureRepeated {
                runBlocking {
                    val stream = loopback.client.openStream()
                    try {
                        request.position(0)
                        stream.writeFully(request, IO_TIMEOUT)
                        stream.shutdownSend()
                        stream.readToEnd()
                    } finally {
                        stream.close()
                    }
                }
            }
        } finally {
            request.freeIfNeeded()
        }
    }

    /** 1 MiB client to server on a fresh stream per iteration, in 64 KiB writes, until the server's FIN. */
    @Test
    fun bulk1MiB() {
        val chunk = filled(CHUNK, echo = false)
        try {
            benchmarkRule.measureRepeated {
                runBlocking {
                    val stream = loopback.client.openStream()
                    try {
                        repeat(1024 * 1024 / CHUNK) {
                            chunk.position(0)
                            chunk.setLimit(CHUNK)
                            stream.writeFully(chunk, IO_TIMEOUT)
                        }
                        stream.shutdownSend()
                        stream.readToEnd()
                    } finally {
                        stream.close()
                    }
                }
            }
        } finally {
            chunk.freeIfNeeded()
        }
    }

    private fun longLivedRoundTrip(size: Int) {
        val stream: QuicByteStream = runBlocking { loopback.client.openStream() }
        val request = filled(size, echo = true)
        try {
            // One unmeasured exchange proves the echo path end to end under R8 before timing starts.
            runBlocking {
                stream.writeFully(request, IO_TIMEOUT)
                assertEquals(size, stream.readExactly(size))
            }
            benchmarkRule.measureRepeated {
                runBlocking {
                    request.position(0)
                    stream.writeFully(request, IO_TIMEOUT)
                    stream.readExactly(size)
                }
            }
        } finally {
            request.freeIfNeeded()
            runBlocking {
                stream.shutdownSend()
                stream.close()
            }
        }
    }

    private fun filled(
        size: Int,
        echo: Boolean,
    ): PlatformBuffer {
        val buffer = loopback.client.bufferFactory.allocate(size)
        buffer.writeByte(if (echo) ECHO_MARKER else 'D'.code.toByte())
        repeat(size - 1) { buffer.writeByte('r'.code.toByte()) }
        buffer.resetForRead()
        return buffer
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
