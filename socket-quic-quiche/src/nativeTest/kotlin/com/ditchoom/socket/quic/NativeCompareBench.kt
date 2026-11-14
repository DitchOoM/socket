@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.socket.connect
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import platform.posix.CLOCK_PROCESS_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.getenv
import platform.posix.timespec
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Comparison workload, not a regression test: inert unless NATIVE_BENCH is set to one of
 * `qrr` (QUIC long-lived stream, 64 B echo), `qspr` (QUIC stream per request), `qbulk` (QUIC, 16 MiB per
 * stream in 64 KiB writes) or `tcp` (this library's TCP socket, 64 B echo). Client and server share this
 * process over 127.0.0.1, exactly like benchmarks/jvm. NATIVE_BENCH_WARMUP / NATIVE_BENCH_SECONDS (default 2 / 10)
 * bound the discarded warm-up and the measured window.
 *
 * Prints one `RESULT` line in the format every program in benchmarks/native-compare prints. See that
 * directory's README.
 */
abstract class NativeCompareBench {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** The process's peak resident set as the OS reports it: bytes on macOS (`ru_maxrss`), KiB on Linux (`VmHWM`). */
    abstract fun maxRssRaw(): Long

    private val options =
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

    @Test
    fun compare() {
        val workload = getenv("NATIVE_BENCH")?.toKString()?.takeIf { it in WORKLOADS } ?: return
        val warmup = (getenv("NATIVE_BENCH_WARMUP")?.toKString()?.toIntOrNull() ?: 2).seconds
        val window = (getenv("NATIVE_BENCH_SECONDS")?.toKString()?.toIntOrNull() ?: 10).seconds
        if (workload == "tcp") {
            runQuicTest(timeout = 10.minutes) { tcp(warmup.inWholeSeconds, window.inWholeSeconds) }
        } else {
            runQuicTest(timeout = 10.minutes) {
                withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { connections { coroutineScope { streams().collect { launch { serve(it) } } } } }
                    try {
                        withQuicConnection("127.0.0.1", port, options, timeout = 10.minutes) {
                            when (workload) {
                                "qrr" -> longLived(this, warmup.inWholeSeconds, window.inWholeSeconds)
                                "qspr" -> streamPerRequest(this, warmup.inWholeSeconds, window.inWholeSeconds)
                                else -> bulk(this, warmup.inWholeSeconds, window.inWholeSeconds)
                            }
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
    }

    private suspend fun longLived(
        scope: QuicScope,
        warmup: Long,
        window: Long,
    ) {
        val stream = scope.openStream()
        val request = request(scope.bufferFactory.allocate(SIZE))
        val run =
            Run("quic", "qrr") {
                request.position(0)
                stream.writeFully(request, 30.seconds)
                stream.readExactly(SIZE)
            }
        run.go(warmup, window)
        stream.shutdownSend()
        stream.close()
        request.freeIfNeeded()
    }

    private suspend fun streamPerRequest(
        scope: QuicScope,
        warmup: Long,
        window: Long,
    ) {
        val request = request(scope.bufferFactory.allocate(SIZE))
        val run =
            Run("quic", "qspr") {
                val stream = scope.openStream()
                try {
                    request.position(0)
                    stream.writeFully(request, 30.seconds)
                    stream.shutdownSend()
                    stream.readToEnd()
                } finally {
                    stream.close()
                }
            }
        run.go(warmup, window)
        request.freeIfNeeded()
    }

    private suspend fun bulk(
        scope: QuicScope,
        warmup: Long,
        window: Long,
    ) {
        val chunk = scope.bufferFactory.allocate(CHUNK)
        repeat(CHUNK) { chunk.writeByte('D'.code.toByte()) }
        // One op is 16 MiB on its own stream; the window ends after the op that crosses it, and the rate
        // is bytes over the time those whole ops took.
        val run =
            Run("quic", "qbulk") {
                val stream = scope.openStream()
                try {
                    repeat(BULK_MIB * (1024 * 1024 / CHUNK)) {
                        chunk.position(0)
                        chunk.setLimit(CHUNK)
                        stream.writeFully(chunk, 30.seconds)
                    }
                    stream.shutdownSend()
                    stream.readToEnd()
                } finally {
                    stream.close()
                }
            }
        run.go(warmup, window, bytesPerOp = BULK_MIB.toLong() * 1024 * 1024)
        chunk.freeIfNeeded()
    }

    private suspend fun tcp(
        warmup: Long,
        window: Long,
    ) {
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = ServerSocket.allocate()
        val accepted = server.bind(0, "127.0.0.1")
        serverScope.launch { accepted.collect { peer -> launch { echo(peer) } } }
        val client = ClientSocket.connect(server.port(), "127.0.0.1")
        val request = BufferFactory.Default.allocate(SIZE)
        repeat(SIZE) { request.writeByte('r'.code.toByte()) }
        request.resetForRead()
        Run("tcp", "tcp") {
            request.position(0)
            client.writeFully(request, 30.seconds)
            var got = 0
            while (got < SIZE) {
                when (val r = client.read(30.seconds)) {
                    is ReadResult.Data ->
                        try {
                            got += r.buffer.remaining()
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End, ReadResult.Reset -> break
                }
            }
        }.go(warmup, window)
        client.close()
        server.close()
        serverScope.cancel()
        request.freeIfNeeded()
    }

    private suspend fun echo(peer: ClientSocket) {
        try {
            while (true) {
                when (val r = peer.read(10.minutes)) {
                    is ReadResult.Data ->
                        try {
                            peer.writeFully(r.buffer, 30.seconds)
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End, ReadResult.Reset -> break
                }
            }
        } catch (_: Exception) {
        } finally {
            peer.close()
        }
    }

    /** Echoes a stream whose first byte is 'E'; any other stream is drained and answered with FIN alone. */
    private suspend fun serve(stream: QuicByteStream) {
        try {
            var echo = false
            var first = true
            while (true) {
                when (val r = stream.read(10.minutes)) {
                    is ReadResult.Data ->
                        try {
                            if (first) {
                                echo = r.buffer[r.buffer.position()] == 'E'.code.toByte()
                                first = false
                            }
                            if (echo) stream.writeFully(r.buffer, 30.seconds)
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

    private fun request(buf: PlatformBuffer): PlatformBuffer {
        buf.writeByte('E'.code.toByte())
        repeat(SIZE - 1) { buf.writeByte('r'.code.toByte()) }
        buf.resetForRead()
        return buf
    }

    private suspend fun QuicByteStream.readExactly(expected: Int) {
        var got = 0
        while (got < expected) {
            when (val r = read(30.seconds)) {
                is ReadResult.Data ->
                    try {
                        got += r.buffer.remaining()
                    } finally {
                        r.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> return
            }
        }
    }

    private suspend fun QuicByteStream.readToEnd() {
        while (true) {
            when (val r = read(30.seconds)) {
                is ReadResult.Data -> r.buffer.freeIfNeeded()
                ReadResult.End, ReadResult.Reset -> return
            }
        }
    }

    /** Runs [op] back to back: [warmup] seconds discarded, then [window] seconds timed per op with a monotonic clock. */
    private inner class Run(
        val stack: String,
        val workload: String,
        val op: suspend () -> Unit,
    ) {
        suspend fun go(
            warmup: Long,
            window: Long,
            bytesPerOp: Long = 0,
        ) {
            val warmStart = TimeSource.Monotonic.markNow()
            while (warmStart.elapsedNow() < warmup.seconds) op()
            var samples = LongArray(1 shl 16)
            var n = 0
            val cpu0 = cpuMicros()
            println("MEASURING")
            val start = TimeSource.Monotonic.markNow()
            while (start.elapsedNow() < window.seconds) {
                val t = TimeSource.Monotonic.markNow()
                op()
                if (n == samples.size) samples = samples.copyOf(n * 2)
                samples[n++] = t.elapsedNow().inWholeNanoseconds
            }
            val secs = start.elapsedNow().inWholeNanoseconds / 1e9
            val cpu = cpuMicros() - cpu0
            samples.sort(0, n)

            fun pct(p: Double) = samples[minOf(n - 1, (n * p).toInt())] / 1000.0
            println(
                "RESULT stack=kotlin-native-ours workload=$workload ops=$n seconds=$secs p50_us=${pct(0.5)} p99_us=${pct(0.99)} " +
                    "p999_us=${pct(0.999)} ops_per_s=${n / secs} mib_per_s=${n * bytesPerOp / secs / 1048576.0} " +
                    "cpu_us=$cpu maxrss_raw=${maxRss()}",
            )
        }

        /** User + system CPU time of the whole process, all threads. */
        private fun cpuMicros(): Long =
            memScoped {
                val ts = alloc<timespec>()
                clock_gettime(CLOCK_PROCESS_CPUTIME_ID.convert(), ts.ptr)
                ts.tv_sec * 1_000_000L + ts.tv_nsec / 1000
            }

        private fun maxRss(): Long = maxRssRaw()
    }

    private companion object {
        val WORKLOADS = setOf("qrr", "qspr", "qbulk", "tcp")
        const val SIZE = 64
        const val CHUNK = 64 * 1024
        const val BULK_MIB = 16
    }
}
