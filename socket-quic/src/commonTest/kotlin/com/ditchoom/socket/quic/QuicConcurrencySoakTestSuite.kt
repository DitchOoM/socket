package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **concurrency + soak** test suite (issue #87, suite #2). Drives many concurrent streams,
 * many concurrent connections, and a sustained sequential echo loop, asserting the driver does not
 * hang, does not surface an uncaught driver-scope exception (the block would rethrow), and — in the
 * soak test — does not leak a single native buffer across the connection's whole lifecycle. This is
 * the lifecycle / race / leak class behind the recent flake fixes (#79, #82).
 *
 * Same 3-tier shape as [QuicImpairmentTestSuite] / [QuicPassiveMigrationTestSuite]: each platform
 * supplies a [testTlsConfig]; the test bodies are inherited. (Android's equivalent is a parallel copy
 * in `androidInstrumentedTest`, which can't see `commonTest` — see `AndroidQuicConcurrencySoakTests`.)
 *
 * **Leak assertion.** The soak test injects a [TrackingBufferFactory] as the client's
 * [TransportConfig.bufferFactory]. That factory feeds *both* the driver's internal buffers and every
 * stream-read buffer (verified: `connectionOptions.bufferFactory` flows into `QuicheDriver` and
 * `QuicheStreamByteStream`). After `withQuicConnection` returns — i.e. the connection is fully closed —
 * `assertNoLeaks()` requires every one of those buffers to have been freed: the read buffers by the
 * test (read transfers ownership; we `freeIfNeeded()` each), the driver internals by the framework's
 * teardown. A single missed free fails with the leaking allocation's stack trace. `TrackingBufferFactory`
 * is not concurrency-safe, so the leak assertion lives in the *sequential* soak test only; the
 * concurrency tests use the default factory and still free every read buffer.
 *
 * **Determinism.** Fixed, bounded workloads (exact stream/connection/round counts) with exact-content
 * assertions — not probabilistic flake-catchers. Sizes are tuned to finish well inside `runQuicTest`'s
 * 15 s cap on loopback.
 */
abstract class QuicConcurrencySoakTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            // Scaled so it stays above the (also-scaled) runQuicTest cap on a loaded runner — a soak run
            // that legitimately takes longer under load must not trip an idle-timeout mid-transfer.
            idleTimeout = 30.seconds.scaled,
        )

    // ---- tests -------------------------------------------------------------------------------------

    /** Open [CONCURRENT_STREAMS] streams at once on one connection; assert every one round-trips. */
    @Test
    fun manyConcurrentStreamsOnOneConnectionRoundTrip() =
        runQuicTest {
            wrapTestBody {
                coroutineScope {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                        val serverJob = launch { connections { echoEveryStream() } }
                        try {
                            withQuicConnection("127.0.0.1", port, options, timeout = 15.seconds.scaled) {
                                val results =
                                    (0 until CONCURRENT_STREAMS)
                                        .map { i ->
                                            async {
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

    /**
     * Whether the platform supports multiple INDEPENDENT connections to the SAME endpoint at once.
     *
     * True for the quiche-backed platforms (JVM/Linux). Apple's Network.framework allows only ONE
     * multiplex QUIC group per (host, port) endpoint per process — a second concurrent group to the
     * same endpoint fails its handshake with POSIX ENOMEM (proven deterministically against both an
     * in-process listener and a public endpoint; different endpoints work fine). The NW model is to
     * multiplex STREAMS over one connection instead — exactly what
     * [manyConcurrentStreamsOnOneConnectionRoundTrip] exercises — so the Apple member overrides this
     * to false and that test self-skips. (Issue #112.)
     */
    protected open fun supportsConcurrentConnectionsToSameEndpoint(): Boolean = true

    /** Open [CONCURRENT_CONNECTIONS] connections at once, one echo each; assert every one round-trips. */
    @Test
    fun manyConnectionsConcurrentlyRoundTrip() =
        runQuicTest {
            wrapTestBody {
                if (!supportsConcurrentConnectionsToSameEndpoint()) return@wrapTestBody
                coroutineScope {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                        val serverJob = launch { connections { echoEveryStream() } }
                        try {
                            val results =
                                (0 until CONCURRENT_CONNECTIONS)
                                    .map { i ->
                                        async {
                                            withQuicConnection("127.0.0.1", port, options, timeout = 15.seconds.scaled) {
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

    /**
     * Sustained sequential echo over one stream for [SOAK_ROUNDS] rounds, then assert no
     * **per-operation** buffer leak. A buffer leaked each round (e.g. a missed read-buffer free) grows
     * the client's live-buffer count to ~[SOAK_ROUNDS]; a leak-free connection leaves only the driver's
     * bounded recv-buffer pool residual (cap 64) plus a handful of straggler buffers — O(1) in the round
     * count. [SOAK_ROUNDS] is chosen well above that residual so the bound cleanly separates the two.
     *
     * (We assert a bounded residual rather than exactly zero because the driver's recv pool legitimately
     * retains buffers: an in-flight recv buffer returned to the pool *after* its `clear()` on teardown
     * repopulates the pool — GC-benign on JVM, freed on K/N — see `QuicheDriver.udpReaderLoop` /
     * `recvBufPool.clear`. That residual is O(1), not O(rounds), so it can't mask a per-op leak here.)
     */
    @Test
    fun sustainedEchoLoopHasNoPerOperationLeak() =
        runQuicTest {
            wrapTestBody {
                val tracking = TrackingBufferFactory()
                coroutineScope {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                        val serverJob = launch { connections { echoEveryStream() } }
                        try {
                            withQuicConnection(
                                "127.0.0.1",
                                port,
                                options,
                                TransportConfig(bufferFactory = tracking),
                                timeout = 15.seconds.scaled,
                            ) {
                                val stream = openStream()
                                for (round in 0 until SOAK_ROUNDS) {
                                    assertEquals("round-$round", stream.echoExact("round-$round"), "soak round $round did not round-trip")
                                }
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
                // Connection fully closed. A per-op leak would leave ~SOAK_ROUNDS live buffers; leak-free
                // leaves only the O(1) bounded pool residual.
                val live = tracking.liveCount
                assertTrue(
                    live <= MAX_RESIDUAL_BUFFERS,
                    "soak left $live live client buffers (> $MAX_RESIDUAL_BUFFERS over $SOAK_ROUNDS rounds) — a per-operation buffer leak",
                )
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Server side: echo every stream on this connection back to the client, each in its own coroutine. */
    private suspend fun QuicScope.echoEveryStream() {
        streams().collect { stream ->
            launch {
                try {
                    while (true) {
                        val data = stream.read(15.seconds.scaled)
                        if (data is ReadResult.Data) {
                            stream.write(data.buffer, 10.seconds.scaled)
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

    /** Write [payload], read exactly [payload].length bytes back, freeing every read buffer (ownership transfers to us). */
    private suspend fun QuicByteStream.echoExact(payload: String): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        try {
            write(out, 10.seconds.scaled)
        } finally {
            out.freeNativeMemory()
        }
        return readExactly(payload.length, 10.seconds.scaled)
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
                r.buffer.freeIfNeeded() // read transfers buffer ownership to us (see QuicheStreamAdapter)
            } else {
                break
            }
        }
        return sb.toString()
    }

    private companion object {
        private const val CONCURRENT_STREAMS = 20
        private const val CONCURRENT_CONNECTIONS = 8

        /** Large enough that a per-op leak (~SOAK_ROUNDS live buffers) dwarfs the O(1) pool residual. */
        private const val SOAK_ROUNDS = 128

        /** Driver recv-buffer pool cap (64) + straggler headroom — the O(1) leak-free residual ceiling. */
        private const val MAX_RESIDUAL_BUFFERS = 80
    }
}
