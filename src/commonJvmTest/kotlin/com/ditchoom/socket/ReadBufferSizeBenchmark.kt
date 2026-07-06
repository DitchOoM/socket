package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.runBlocking
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Exploratory benchmark: does the receive-buffer size (userspace read buffer, set via
 * [IoTuning.readBufferSize]) actually change bulk-read throughput?
 *
 * A server thread blasts a fixed total over loopback; the client drains it to EOF with a range of
 * read-buffer sizes and we report throughput, the number of `read()` calls, and pool allocations.
 * This isolates the userspace read buffer as the only variable — every size below is non-default so
 * `effectiveReadBufferSize` returns it verbatim (65536 is the "query SO_RCVBUF" sentinel, so 65535
 * stands in for ~64 KiB), and SO_RCVBUF is left at the OS default (constant across runs).
 *
 * Opt-in: the body no-ops unless the `SOCKET_READBUF_BENCHMARK` env var is set, so CI never spends
 * time on it (Gradle forwards the environment to the test JVM, so no build-config wiring is needed).
 * Run explicitly:
 *   SOCKET_READBUF_BENCHMARK=1 ./gradlew :jvmTest --tests "com.ditchoom.socket.ReadBufferSizeBenchmark"
 */
class ReadBufferSizeBenchmark {
    private class CountingBufferFactory(
        private val delegate: BufferFactory = BufferFactory.Default,
    ) : BufferFactory by delegate {
        val allocations = AtomicInteger(0)

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            allocations.incrementAndGet()
            return delegate.allocate(size, byteOrder)
        }
    }

    private data class Sample(
        val readBufferSize: Int,
        val throughputMBps: Double,
        val reads: Long,
        val allocations: Int,
    )

    private fun drainOnce(
        readBufferSize: Int,
        totalBytes: Long,
    ): Sample {
        val server = ServerSocket(0)
        val port = server.localPort
        val chunk = ByteArray(1 shl 20) { 0x41 } // 1 MiB blast chunk
        thread(isDaemon = true, name = "blast-server") {
            try {
                val c = server.accept()
                val out = DataOutputStream(c.getOutputStream().buffered(1 shl 20))
                var sent = 0L
                while (sent < totalBytes) {
                    val n = minOf(chunk.size.toLong(), totalBytes - sent).toInt()
                    out.write(chunk, 0, n)
                    sent += n
                }
                out.flush()
                c.close()
            } catch (_: Exception) {
                // client hung up
            }
        }

        val counting = CountingBufferFactory()
        var bytes = 0L
        var reads = 0L
        val elapsed =
            runBlocking {
                val config =
                    TransportConfig(
                        bufferFactory = counting,
                        io = IoTuning(tcpNoDelay = true, readBufferSize = readBufferSize),
                    )
                val socket = ClientSocket.connect(port, "localhost", config)
                val mark = TimeSource.Monotonic.markNow()
                try {
                    loop@ while (true) {
                        when (val r = socket.read(30.seconds)) {
                            is ReadResult.Data -> {
                                bytes += r.buffer.remaining()
                                reads++
                                (r.buffer as PlatformBuffer).freeNativeMemory()
                            }
                            else -> break@loop // End or Reset
                        }
                    }
                } finally {
                    socket.close()
                }
                mark.elapsedNow()
            }
        server.close()

        val seconds = elapsed.inWholeMicroseconds / 1_000_000.0
        val mbPerSec = (bytes / (1024.0 * 1024.0)) / seconds
        check(bytes >= totalBytes) { "short read: got $bytes of $totalBytes at size=$readBufferSize" }
        return Sample(readBufferSize, mbPerSec, reads, counting.allocations.get())
    }

    @Test
    fun readBufferSizeThroughputMatrix() {
        if (System.getenv("SOCKET_READBUF_BENCHMARK").isNullOrBlank()) {
            println("ReadBufferSizeBenchmark skipped; set SOCKET_READBUF_BENCHMARK=1 to run.")
            return
        }
        val totalBytes = 256L * 1024 * 1024 // 256 MiB per iteration
        val sizes = listOf(4 * 1024, 16 * 1024, 65535, 128 * 1024, 256 * 1024, 512 * 1024)
        val measuredIters = 3

        // Warm up JIT / class loading once per size (discarded).
        for (size in sizes) drainOnce(size, 32L * 1024 * 1024)

        val results =
            sizes.map { size ->
                val runs = (1..measuredIters).map { drainOnce(size, totalBytes) }
                // Median throughput; reads/allocations are deterministic enough to take the first.
                val median = runs.map { it.throughputMBps }.sorted()[measuredIters / 2]
                Sample(size, median, runs.first().reads, runs.first().allocations)
            }

        println("=== read-buffer-size throughput ($totalBytes bytes/iter, median of $measuredIters) ===")
        println("  readBufSize |  MB/s  |    reads | allocs")
        println("  ------------+--------+----------+-------")
        for (r in results) {
            println(
                "  ${r.readBufferSize.toString().padStart(11)} |" +
                    " ${r.throughputMBps.toInt().toString().padStart(6)} |" +
                    " ${r.reads.toString().padStart(8)} |" +
                    " ${r.allocations.toString().padStart(6)}",
            )
        }
        val best = results.maxByOrNull { it.throughputMBps }!!
        val at64k = results.first { it.readBufferSize == 65535 }
        val gain = (best.throughputMBps - at64k.throughputMBps) / at64k.throughputMBps * 100.0
        println(
            "  best=${best.readBufferSize} @ ${best.throughputMBps.toInt()} MB/s; " +
                "~64K @ ${at64k.throughputMBps.toInt()} MB/s (best is ${gain.toInt()}% faster)",
        )
    }
}
