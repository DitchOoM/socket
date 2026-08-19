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
 *
 * ## No `supportsConcurrentConnectionsToSameEndpoint()` escape hatch
 * The two `manyConnections*` tests used to sit behind one, defaulting to `true`, "because Network.
 * framework allows only ONE multiplex QUIC group per (host, port) endpoint per process, so the Apple
 * member overrides this to false (issue #112)". That described the **deleted** NW QUIC backend; since
 * the June 2026 pivot Apple runs the same Cloudflare quiche engine over its own UDP sockets, and no
 * member has overridden the hook on any platform. What remained was a live mechanism for making two of
 * these five tests disappear: `false` skipped them by returning early, and an early return is reported
 * as a **pass** — silently on Kotlin/Native, where there is no `assume`.
 *
 * A platform that genuinely cannot open concurrent connections to one endpoint must record a typed skip
 * instead, as [QuicActiveMigrationTestSuite] requires: override [wrapTestBody] on the member class and
 * call `recordSkip(TheMember::class, reason, gate)` without invoking the block, which emits the
 * `[TEST-SKIPPED]` marker the CI skip inventory counts. (That gate is per-suite, not per-test, which is
 * the honest granularity: a platform with that limitation cannot run *either* `manyConnections*` test,
 * and splitting the suite is cheaper than reintroducing a per-test boolean.)
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
     * Higher-concurrency stream-mux stress: [HIGH_CONCURRENT_STREAMS] streams opened at once on ONE
     * connection (~3x [CONCURRENT_STREAMS]), to push the driver's per-stream bookkeeping and the QUIC
     * `initial_max_streams` credit flow harder than the baseline. Same shape and assertions as
     * [manyConcurrentStreamsOnOneConnectionRoundTrip]. CI-safe: the stream count is fixed and bounded; a
     * loaded runner is given proportionally more wall-clock via `.scaled` (the `runQuicTest` cap and every
     * per-op timeout), never a weaker assertion. Works on every backend including Apple (single connection,
     * stream multiplexing — the NW model).
     */
    @Test
    fun manyConcurrentStreamsHighConcurrencyRoundTrip() =
        runQuicTest(timeout = 25.seconds) {
            wrapTestBody {
                withDiffDebug("manyConcurrentStreamsHighConcurrency", { "scale=${testTimeScale()} streams=$HIGH_CONCURRENT_STREAMS" }) {
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                            val serverJob = launch { connections { echoEveryStream() } }
                            try {
                                withQuicConnection("127.0.0.1", port, options, timeout = 20.seconds.scaled) {
                                    val results =
                                        (0 until HIGH_CONCURRENT_STREAMS)
                                            .map { i ->
                                                async {
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

    /** Open [CONCURRENT_CONNECTIONS] connections at once, one echo each; assert every one round-trips. */
    @Test
    fun manyConnectionsConcurrentlyRoundTrip() =
        runQuicTest {
            wrapTestBody {
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
     * Higher-concurrency connection stress: [HIGH_CONCURRENT_CONNECTIONS] independent connections opened
     * at once (~3x [CONCURRENT_CONNECTIONS]), each round-tripping one echo. Exercises simultaneous
     * handshakes + per-connection driver setup/teardown under heavier load than the baseline. Same
     * assertions as [manyConnectionsConcurrentlyRoundTrip]. CI-safe: the connection count is fixed and
     * bounded; only the wall-clock budgets grow via `.scaled`.
     */
    @Test
    fun manyConnectionsHighConcurrencyRoundTrip() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withDiffDebug("manyConnectionsHighConcurrency", { "scale=${testTimeScale()} connections=$HIGH_CONCURRENT_CONNECTIONS" }) {
                    coroutineScope {
                        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                            val serverJob = launch { connections { echoEveryStream() } }
                            try {
                                val results =
                                    (0 until HIGH_CONCURRENT_CONNECTIONS)
                                        .map { i ->
                                            async {
                                                withQuicConnection("127.0.0.1", port, options, timeout = 25.seconds.scaled) {
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
        return readExactly(payload.length, 10.seconds.scaled, payload)
    }

    /**
     * Reads [total] bytes, then decodes — deliberately in that order (#401).
     *
     * The previous version decoded each chunk as it arrived, so when the echoed bytes were **not**
     * what was sent it died inside `readString` with a bare
     * `MalformedInputException: Input length = 1` and discarded the very evidence needed to diagnose
     * it. That is a data-integrity symptom, not a timing one: something delivered bytes that were
     * never sent. Capturing the raw bytes first makes the next occurrence self-diagnosing instead
     * of merely reproducible-if-you-are-lucky, which is what unblocked #291/#292.
     *
     * Tests may use `ByteArray` freely; the no-ByteArray rule is production-only.
     */
    private suspend fun QuicByteStream.readExactly(
        total: Int,
        timeout: Duration,
        expected: String,
    ): String {
        val received = ArrayList<Byte>(total)
        var chunks = 0
        while (received.size < total) {
            val r = read(timeout)
            if (r is ReadResult.Data) {
                chunks++
                repeat(r.buffer.remaining()) { received.add(r.buffer.readByte()) }
                r.buffer.freeIfNeeded() // read transfers buffer ownership to us (see QuicheStreamAdapter)
            } else {
                break
            }
        }
        val bytes = received.toByteArray()
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw AssertionError(describeCorruption(expected, bytes, chunks), e)
        }
    }

    /** The evidence the bare `MalformedInputException` used to throw away (#401). */
    private fun describeCorruption(
        expected: String,
        received: ByteArray,
        chunks: Int,
    ): String {
        val expectedBytes = expected.encodeToByteArray()
        val divergence =
            received.indices.firstOrNull { i ->
                i >= expectedBytes.size || received[i] != expectedBytes[i]
            } ?: -1
        return listOf(
            "#401: echoed bytes are not valid UTF-8 — the peer returned bytes that were never sent.",
            "  expected : \"$expected\" (${expectedBytes.size} bytes)",
            "  expected : ${expectedBytes.toHex()}",
            "  received : ${received.toHex()} (${received.size} bytes)",
            "  printable: ${received.toPrintable()}",
            "  chunks   : $chunks read(s); first divergence at index $divergence",
        ).joinToString("\n")
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { b ->
            val v = b.toInt() and 0xFF
            "${HEX[v shr 4]}${HEX[v and 0xF]}"
        }

    private fun ByteArray.toPrintable(): String {
        val sb = StringBuilder(size)
        for (b in this) {
            sb.append(if (b >= 0x20 && b < 0x7F) b.toInt().toChar() else '.')
        }
        return sb.toString()
    }

    private companion object {
        private const val HEX = "0123456789abcdef"

        private const val CONCURRENT_STREAMS = 20
        private const val CONCURRENT_CONNECTIONS = 8

        // Higher-concurrency variants (~3x the baseline). Bounded so they stay CI-safe under the
        // (scaled) runQuicTest caps on a loaded loopback runner; only the timeouts scale, not the counts.
        private const val HIGH_CONCURRENT_STREAMS = 64
        private const val HIGH_CONCURRENT_CONNECTIONS = 24

        /** Large enough that a per-op leak (~SOAK_ROUNDS live buffers) dwarfs the O(1) pool residual. */
        private const val SOAK_ROUNDS = 128

        /** Driver recv-buffer pool cap (64) + straggler headroom — the O(1) leak-free residual ceiling. */
        private const val MAX_RESIDUAL_BUFFERS = 80
    }
}
