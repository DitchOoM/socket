package com.ditchoom.socket.quic.core

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 64-byte echo round trips from this device to a `RemoteLoadServer`, over M connections each driven by a
 * thread of its own, on either engine: `client=core` (run-to-completion core, shared loops) or
 * `client=legacy` (the driver-per-connection engine behind `withQuicConnection`). Runs only with the
 * `remoteHost` argument; results go to logcat (tag CoreRemoteLoad).
 *
 * Arguments: remoteHost, remotePort, client, counts (default "1,64"), warmupS (3), measureS (10).
 */
@RunWith(AndroidJUnit4::class)
class CoreRemoteLoad {
    private val args = InstrumentationRegistry.getArguments()

    private fun arg(
        name: String,
        default: String,
    ): String = args.getString(name, default)

    @Test
    fun load() {
        val host = arg("remoteHost", "")
        assumeTrue("no remoteHost argument", host.isNotEmpty())
        val port = arg("remotePort", "0").toInt()
        val engine = Engine.valueOf(arg("client", "core").uppercase())
        for (m in arg("counts", "1,64").split(',').map { it.trim().toInt() }) {
            run(engine, host, port, m, arg("warmupS", "3").toLong(), arg("measureS", "10").toLong())
        }
    }

    private enum class Engine { CORE, LEGACY }

    private fun run(
        engine: Engine,
        host: String,
        port: Int,
        m: Int,
        warmupS: Long,
        measureS: Long,
    ) {
        val stop = CompletableDeferred<Unit>()
        val streams =
            List(m) {
                val ready = CompletableDeferred<QuicByteStream>()
                thread(name = "load-conn-$it", isDaemon = true) {
                    try {
                        runBlocking(Dispatchers.IO) {
                            when (engine) {
                                Engine.CORE -> {
                                    val client =
                                        coreConnect(
                                            mismatch = MismatchResponse.Fail,
                                            host = host,
                                            port = port,
                                            serverName = "localhost",
                                            options = OPTIONS,
                                            factory = BufferFactory.network(),
                                            api = loadQuicheApi(),
                                            timeout = 24.hours,
                                            readPolicy = ReadPolicy.Bounded(30.seconds),
                                            writePolicy = WritePolicy.Bounded(30.seconds),
                                        )
                                    ready.complete(client.connection.openStream())
                                    stop.await()
                                    client.connection.close(QuicError.NoError)
                                }
                                Engine.LEGACY ->
                                    withQuicConnection(host, port, OPTIONS, timeout = 24.hours) {
                                        ready.complete(openStream())
                                        stop.await()
                                    }
                            }
                        }
                    } catch (t: Throwable) {
                        ready.completeExceptionally(t)
                    }
                }
                ready
            }.map { runBlocking { it.await() } }
        try {
            measure(engine, m, streams, warmupS, measureS)
        } finally {
            stop.complete(Unit)
        }
    }

    private fun measure(
        engine: Engine,
        m: Int,
        streams: List<QuicByteStream>,
        warmupS: Long,
        measureS: Long,
    ) {
        val latencies = Array(m) { LongArray(MAX_SAMPLES) }
        val counts = IntArray(m)
        val startNs = System.nanoTime()
        val warmEnd = startNs + warmupS * NANOS
        val end = warmEnd + measureS * NANOS
        var cpuAtWarmEnd = 0L
        var cpuAtEnd = 0L
        var allocAtWarmEnd = 0L
        var allocAtEnd = 0L
        val sampler =
            thread(isDaemon = true) {
                Thread.sleep(warmupS * 1000)
                cpuAtWarmEnd = Process.getElapsedCpuTime()
                allocAtWarmEnd = javaHeapBytesAllocated()
                Thread.sleep(measureS * 1000)
                cpuAtEnd = Process.getElapsedCpuTime()
                allocAtEnd = javaHeapBytesAllocated()
            }
        val workers =
            streams.mapIndexed { i, stream ->
                thread(name = "load-app-$i") {
                    runBlocking {
                        val factory = BufferFactory.network()
                        val request = factory.allocate(64)
                        while (true) {
                            val t0 = System.nanoTime()
                            if (t0 >= end) break
                            request.resetForWrite()
                            request.writeByte('E'.code.toByte())
                            repeat(63) { request.writeByte('r'.code.toByte()) }
                            request.resetForRead()
                            stream.writeFully(request, 30.seconds)
                            var got = 0
                            while (got < 64) {
                                val r = stream.read(30.seconds) { got += it.remaining() }
                                if (r !is ScopedReadResult.Data<*>) error("stream ended after $got of 64 bytes")
                            }
                            val dt = System.nanoTime() - t0
                            if (t0 >= warmEnd && counts[i] < MAX_SAMPLES) latencies[i][counts[i]++] = dt
                        }
                    }
                }
            }
        workers.forEach { it.join() }
        sampler.join()
        val all = LongArray(counts.sum())
        var at = 0
        for (i in 0 until m) {
            latencies[i].copyInto(all, at, 0, counts[i])
            at += counts[i]
        }
        all.sort()
        val cpuMs = (cpuAtEnd - cpuAtWarmEnd).toDouble()
        report(
            "%-6s M=%3d ops=%d %8.0f op/s p50 %8.1f us p99 %8.1f us cpu %.0f us/op (%.2f cores) java-heap %.0f B/op".format(
                engine.name,
                m,
                all.size,
                all.size / measureS.toDouble(),
                percentileUs(all, 0.50),
                percentileUs(all, 0.99),
                cpuMs * 1000.0 / all.size.coerceAtLeast(1),
                cpuMs / 1000.0 / measureS,
                (allocAtEnd - allocAtWarmEnd).toDouble() / all.size.coerceAtLeast(1),
            ),
        )
    }

    /** The whole process's Java-heap allocation so far, from ART's runtime stats; no per-thread counter exists. */
    private fun javaHeapBytesAllocated(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()

    private fun percentileUs(
        sorted: LongArray,
        q: Double,
    ): Double = if (sorted.isEmpty()) Double.NaN else sorted[(sorted.size * q).toInt().coerceAtMost(sorted.size - 1)] / 1000.0

    private fun report(line: String) {
        Log.i(TAG, line)
        val bundle = Bundle()
        bundle.putString(android.app.Instrumentation.REPORT_KEY_STREAMRESULT, "$line\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
    }

    private companion object {
        const val TAG = "CoreRemoteLoad"
        const val MAX_SAMPLES = 50_000
        const val NANOS = 1_000_000_000L
        val OPTIONS =
            QuicOptions(
                alpnProtocols = listOf("bench"),
                verifyPeer = false,
                idleTimeout = 10.minutes,
                migration = MigrationPolicy.Forbidden,
                flowControl =
                    FlowControl(
                        initialMaxData = 64L * 1024 * 1024,
                        initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                        initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                        initialMaxStreamsBidi = 1_000_000,
                    ),
            )
    }
}
