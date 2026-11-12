@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.getenv
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Profiling workload, not a regression test: inert unless QUIC_PERF=rr or QUIC_PERF=bulk is set, then
 * runs for QUIC_PERF_SECONDS (default 30) with client and server in this process over loopback.
 *
 * - rr:   64-byte request/echo round trips on one long-lived stream.
 * - bulk: 64 KiB writes on one stream, drained by the server.
 *
 * Prints `PERF-END` (ops, bytes, rate) and, per side, quiche's own datagram counters
 * (`PERF-STATS client|server sent=.. recv=.. sentBytes=.. recvBytes=..`), so the client/server datagram
 * split is measured, not assumed. See scripts/native-perf/README.md for the recording recipe.
 */
abstract class NativeQuicPerfTrace {
    abstract fun testTlsConfig(): QuicTlsConfig

    private val options =
        QuicOptions(
            alpnProtocols = listOf("bench"),
            verifyPeer = false,
            idleTimeout = 120.seconds,
            migration = MigrationPolicy.Forbidden,
            flowControl =
                FlowControl(
                    initialMaxData = 64L * 1024 * 1024,
                    initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                    initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                    initialMaxStreamsBidi = 10_000,
                ),
        )

    @Test
    fun perfTrace() {
        val mode = getenv("QUIC_PERF")?.toKString()?.takeIf { it == "rr" || it == "bulk" } ?: return
        val secs = getenv("QUIC_PERF_SECONDS")?.toKString()?.toIntOrNull() ?: 30
        runQuicTest(timeout = (secs + 120).seconds) {
            val serverStats = CompletableDeferred<QuicConnStats?>()
            val serverDone = CompletableDeferred<Unit>()
            withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = testTlsConfig(), quicOptions = options) {
                val serverJob =
                    launch {
                        connections {
                            val driver = driverOf()
                            launch {
                                serverDone.await()
                                serverStats.complete(driver.stats().connStats)
                            }
                            coroutineScope {
                                streams().collect { stream ->
                                    launch {
                                        try {
                                            while (true) {
                                                when (val r = stream.read(60.seconds)) {
                                                    is ReadResult.Data ->
                                                        try {
                                                            if (mode == "rr") stream.writeFully(r.buffer, 30.seconds)
                                                        } finally {
                                                            r.buffer.freeIfNeeded()
                                                        }
                                                    ReadResult.End, ReadResult.Reset -> break
                                                }
                                            }
                                            stream.shutdownSend()
                                        } catch (_: Exception) {
                                        } finally {
                                            stream.close()
                                        }
                                    }
                                }
                            }
                        }
                    }
                try {
                    withQuicConnection("127.0.0.1", port, options, timeout = (secs + 100).seconds) {
                        val stream = openStream()
                        val buf = bufferFactory.allocate(64 * 1024)
                        println("PERF-START mode=$mode secs=$secs")
                        val start = TimeSource.Monotonic.markNow()
                        var ops = 0L
                        var bytes = 0L
                        if (mode == "rr") {
                            while (start.elapsedNow() < secs.seconds) {
                                buf.resetForWrite()
                                repeat(64) { buf.writeByte('r'.code.toByte()) }
                                buf.resetForRead()
                                stream.writeFully(buf, 30.seconds)
                                var got = 0
                                while (got < 64) {
                                    when (val r = stream.read(30.seconds)) {
                                        is ReadResult.Data ->
                                            try {
                                                got += r.buffer.remaining()
                                            } finally {
                                                r.buffer.freeIfNeeded()
                                            }
                                        ReadResult.End, ReadResult.Reset -> break
                                    }
                                }
                                ops++
                            }
                            bytes = ops * 64
                        } else {
                            buf.resetForWrite()
                            repeat(64 * 1024) { buf.writeByte('D'.code.toByte()) }
                            while (start.elapsedNow() < secs.seconds) {
                                buf.position(0)
                                buf.setLimit(64 * 1024)
                                stream.writeFully(buf, 60.seconds)
                                bytes += 64 * 1024
                                ops++
                            }
                        }
                        val el = start.elapsedNow()
                        stream.shutdownSend()
                        while (true) {
                            when (val r = stream.read(60.seconds)) {
                                is ReadResult.Data -> r.buffer.freeIfNeeded()
                                ReadResult.End, ReadResult.Reset -> break
                            }
                        }
                        stream.close()
                        buf.freeIfNeeded()
                        val client = driverOf().stats().connStats
                        serverDone.complete(Unit)
                        val server = withTimeoutOrNull(10.seconds) { serverStats.await() }
                        println("PERF-STATS client ${client?.render()}")
                        println("PERF-STATS server ${server?.render()}")
                        val s = el.inWholeMicroseconds / 1e6
                        println(
                            "PERF-END mode=$mode ops=$ops bytes=$bytes seconds=$s ops_per_s=${ops / s} MiB_per_s=${bytes / s / 1048576.0}",
                        )
                    }
                } finally {
                    serverJob.cancel()
                }
            }
        }
    }
}

private fun QuicConnStats.render() = "sent=$sent recv=$recv sentBytes=$sentBytes recvBytes=$recvBytes lost=$lost"

private fun QuicScope.driverOf(): QuicheDriver = (this as QuicheBackedConnection).quicheDriver
