package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * What a desktop JVM application that shrinks its jar actually runs. Built into a fat jar with every
 * dependency, then shrunk by R8 and by ProGuard (`:benchmark-jvm:shrunkConsumerProbe`), each jar runs
 * this: it names the quiche backend it loaded and times [ROUNDS] 64-byte round trips on one long-lived
 * QUIC stream over loopback.
 *
 * Usage: ShrunkConsumerProbeKt <testcerts dir> <label>
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: ShrunkConsumerProbeKt <testcerts dir> <label>" }
    System.setProperty("bench.certDir", args[0])
    val backend = loadedQuicheBackend()
    val samples =
        QuicLoopback().use { loopback ->
            runBlocking {
                val stream = loopback.client.openStream()
                val request = loopback.client.bufferFactory.allocate(SIZE)
                try {
                    request.writeByte(ECHO_MARKER)
                    repeat(SIZE - 1) { request.writeByte('r'.code.toByte()) }
                    request.resetForRead()

                    suspend fun once(): Long {
                        val start = TimeSource.Monotonic.markNow()
                        request.position(0)
                        stream.writeFully(request, IO_TIMEOUT)
                        check(stream.readExactly(SIZE) == SIZE) { "short echo" }
                        return start.elapsedNow().inWholeNanoseconds
                    }
                    repeat(WARMUP) { once() }
                    LongArray(ROUNDS) { once() }.sorted()
                } finally {
                    request.freeIfNeeded()
                    stream.shutdownSend()
                    stream.close()
                }
            }
        }
    val p50 = samples[samples.size / 2] / 1000.0
    val p99 = samples[samples.size * 99 / 100] / 1000.0
    println(
        "PROBE ${args[1]}: backend=$backend jdk=${System.getProperty("java.version")} " +
            "rounds=$ROUNDS p50_us=${"%.1f".format(p50)} p99_us=${"%.1f".format(p99)}",
    )
    exitProcess(0)
}

private const val SIZE = 64
private const val WARMUP = 2_000
private const val ROUNDS = 5_000
