package com.ditchoom.socket.transport

import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Full-duplex use of one [CodecConnection] on real, distinct threads (#382).
 *
 * ## What this test is, and what it is NOT
 *
 * It is the only coverage in this suite that puts two real threads on one connection at once. Every
 * other test runs under `runTest`, whose scheduler is single-threaded virtual time — exactly right for
 * asserting ordering and interleaving deterministically, and structurally blind to anything that needs
 * two threads.
 *
 * It is **not** a reproduction of the `BufferPool` thread-safety defect it was written for, and it must
 * not be described as one. An adversarial review demonstrated that a `SingleThreaded` pool shared by
 * the writer and the collector corrupts its own structure:
 * ```
 * java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 10
 *   at kotlin.collections.ArrayDeque.removeLast
 *   at com.ditchoom.buffer.pool.SingleThreadedBufferPool.popAtLeast(SingleThreadedBufferPool.kt:138)
 *   at com.ditchoom.buffer.stream.DefaultStreamProcessor.readBufferScoped(BufferStream.kt:744)
 *   at com.ditchoom.socket.transport.CodecConnection.drainFrame(CodecConnection.kt:360)
 * ```
 * with a hang as the other observed outcome. **This harness does not reproduce it.** Reverting the pool
 * to the `SingleThreaded` default leaves this test green — measured across three shapes (two
 * connections; one connection full-duplex; two `limitedParallelism(1)` dispatchers guaranteeing
 * distinct threads) and up to 20 000 round trips. So it does not discriminate the fix, and a green run
 * here is not evidence the pool is safe.
 *
 * The fix it accompanies rests on argument rather than on this test: after #382 the pool is provably
 * touched by the writer's thread, the collector's thread and `close()`; the buffer library documents
 * `SingleThreaded` as "faster but NOT thread-safe" (plain `ArrayDeque` buckets, non-atomic refcounts);
 * and every other pool in this repository shared that way is already declared `MultiThreaded`
 * ([com.ditchoom.socket.ReadBufferSource], QuicheDriver's stream and recv pools).
 *
 * Kept because real-thread full-duplex coverage is worth having even when it is only a smoke test, and
 * because whoever next tries to reproduce the corruption should start by knowing these three shapes
 * already failed to.
 */
class CodecConnectionThreadedPoolTests {
    @Test
    fun fullDuplexTrafficOnRealThreadsDoesNotCorruptTheBufferPool() =
        runBlocking(Dispatchers.Default) {
            withTimeout(60.seconds) {
                val (clientStream, serverStream) = MemoryTransport.createPair(TransportConfig())
                // Two DISTINCT single-slot dispatchers, so the writer and the collector are provably
                // on different threads rather than relying on Dispatchers.Default to split them.
                val writerThread = Dispatchers.Default.limitedParallelism(1)
                val collectorThread = Dispatchers.Default.limitedParallelism(1)
                val client =
                    CodecConnection(
                        stream = clientStream,
                        codec = TestStringCodec,
                        scope = CoroutineScope(writerThread + Job()),
                        outboundCapacity = 64,
                        overflowPolicy = OverflowPolicy.Suspend,
                        config = TransportConfig(),
                    )
                val server = testCodecConnection(serverStream, TestStringCodec)
                try {
                    // The server echoes, so the CLIENT connection is the one under test: its writer
                    // encodes on one thread while its collector decodes on another, both against the
                    // one BufferPool that connection owns. Two connections each used in a single
                    // direction would not share a pool between roles and would prove nothing.
                    val echo =
                        async(Dispatchers.Default) {
                            server.receive().take(MESSAGES).collect { server.send(it) }
                        }
                    val sent =
                        (0 until MESSAGES).map { i ->
                            // Vary the size so the pool's buckets are actually exercised.
                            "m$i:" + "x".repeat(1 + (i * 37) % 3_000)
                        }
                    val collected = async(collectorThread) { client.receive().take(MESSAGES).toList() }
                    for (m in sent) client.send(m)

                    assertEquals(
                        sent,
                        collected.await(),
                        "full-duplex traffic on one connection must round-trip intact: its writer, its " +
                            "collector and close() all share that connection's BufferPool, so it has " +
                            "to be MultiThreaded (#382).",
                    )
                    echo.await()
                } finally {
                    client.close()
                    server.close()
                }
            }
        }

    private companion object {
        /** Enough round trips that a non-thread-safe pool is reliably driven into its failure. */
        const val MESSAGES = 20_000
    }
}
