package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **passive** connection-migration test suite (RFC 9000 §9.3 NAT rebinding). Each platform
 * extends this with a [testTlsConfig] and a [createRebindingProxy] implementation; the test body is
 * inherited, guaranteeing parity across JVM, Linux K/N, and any future common-test platform —
 * exactly like [QuicServerTestSuite] does for the server suite.
 *
 * (Android's equivalent lives in `androidInstrumentedTest`, a separate on-device compilation that
 * doesn't see `commonTest`, so it stays its own copy by necessity — see
 * `AndroidQuicPassiveMigrationTests`.)
 *
 * The client never calls [QuicScope.migrate]; instead the path's *source* address changes
 * underneath it, as a NAT rebind would. A userspace [RebindingProxy] sits between client and the
 * in-process server (no root / netns / tc): the client talks to the proxy, the proxy forwards to
 * the server, and mid-stream the proxy swaps its *upstream* (server-facing) socket for one with a
 * fresh source port. From the server's view that's a single connection (unchanged DCID) whose
 * source 4-tuple suddenly changed. We assert the stream still round-trips afterward, exercising the
 * server's per-source recv_info + `sendInfo.to` egress routing.
 *
 * ## No `supportsPassiveSourceRebind()` escape hatch
 * This suite used to offer one, defaulting to `true`, "because Apple's server does not migrate egress
 * to a rebound source (issue #112)". That justification described the **deleted** Network.framework
 * QUIC backend, and no member has overridden it since: Apple's server has been the same Cloudflare
 * quiche server as JVM/Linux since the June 2026 pivot, doing the same per-source `recv_info` +
 * `sendInfo.to` routing. So the hook sat at `true` on every platform while remaining a live mechanism
 * for making this test *vanish* — a `false` here would have turned the one test in this suite into a
 * green tick that asserted nothing, and on Kotlin/Native there is no `assume`, so the early return is
 * reported as a pass with no trace at all.
 *
 * A platform that genuinely cannot do RFC 9000 §9.3 must therefore record a typed skip instead, exactly
 * as [QuicActiveMigrationTestSuite] requires: override [wrapTestBody] on the member class and call
 * `recordSkip(TheMember::class, reason, gate)` without invoking the block. That emits the
 * `[TEST-SKIPPED]` marker the CI skip inventory greps for, so the gap is counted rather than dissolved
 * into a passing run.
 */
abstract class QuicPassiveMigrationTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Platform-specific NAT-rebind proxy (DatagramChannel on JVM, io_uring/POSIX on Linux K/N). */
    abstract fun createRebindingProxy(serverPort: Int): RebindingProxy

    /**
     * Platform hook for skip-on-missing-native-lib semantics. Default passes through; the JVM
     * subclass overrides to convert `UnsatisfiedLinkError` into an `assumeTrue` skip. Native targets
     * inherit the default no-op — their cinterop quiche binding is fixed at compile time, so there
     * is no skip path and any failure is a real failure (the "must run, never silently skip"
     * discipline `QUIC_MIGRATION_REQUIRE_RUN` enforces on the JVM active-migration test).
     */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private suspend fun QuicByteStream.echoOnce(
        payload: String,
        readTimeout: Duration,
    ): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        // Scoped read (#538): the echo is decoded inside the block and the buffer returns to the
        // driver's pool on the way out — read transfers ownership, and no collector owns what a
        // `deterministic()` factory hands back.
        val resp = read(readTimeout) { it.readString(it.remaining(), Charset.UTF8) }
        return if (resp is ScopedRead.Data) resp.value else "no_data"
    }

    @Test
    fun streamSurvivesPassiveSourceRebind() =
        // Generous whole-test budget. This does connect + echo + a NAT rebind + a
        // post-rebind echo; a passive rebind drops in-flight packets, so the "after"
        // round-trip can need a QUIC PTO-driven retransmit and/or path validation —
        // legitimately several seconds under loss + CI load. (The old 15s default was
        // also inconsistent with the per-op timeouts below, which summed to more than
        // that, so a slow-but-correct run timed out opaquely. Flaky on #103 CI.)
        runQuicTest(timeout = 40.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    // Echo loop: mirror every message back until the stream ends.
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    val data = stream.read(8.seconds)
                                    if (data is ReadResult.Data) {
                                        try {
                                            stream.writeFully(data.buffer, 5.seconds)
                                        } finally {
                                            // read transfers ownership; write is zero-copy and takes none — without this
                                            // free every echoed chunk leaks, and accumulated echo leaks were the #401
                                            // corruption's primer. writeFully because a QUIC write may be partial.
                                            data.buffer.freeIfNeeded()
                                        }
                                    } else {
                                        break
                                    }
                                }
                                stream.close()
                            }
                        }

                    val proxy = createRebindingProxy(port)
                    try {
                        // Run the client INLINE (not in a child launch funneling results through
                        // CompletableDeferred.await): a per-op `withTimeout` throws a
                        // CancellationException, which would cancel a child coroutine silently and
                        // leave an unbounded await() hanging until the whole-test timeout — masking
                        // the real failure as an opaque 15s timeout. Inline, any failure propagates
                        // straight to the test with its true cause and phase.
                        withQuicConnection("127.0.0.1", proxy.proxyPort, testQuicOptions, timeout = 10.seconds) {
                            val stream = openStream()
                            assertEquals("before", stream.echoOnce("before", readTimeout = 5.seconds))

                            // Passive rebind: the proxy's source toward the server changes, with NO
                            // client-side migrate(). The server must keep the stream alive via
                            // per-source recv_info + sendInfo.to routing.
                            proxy.rebind()

                            // Allow the post-rebind round-trip to absorb migration recovery
                            // (retransmit + path validation). Bounded well under the 10s idle timeout
                            // so a genuine "never recovers" still fails promptly rather than hanging.
                            assertEquals(
                                "after",
                                stream.echoOnce("after", readTimeout = 9.seconds),
                                "stream did not round-trip after passive source rebind",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                        proxy.close()
                    }
                }
            }
        }
}

/**
 * A userspace UDP forwarder that simulates a NAT rebind. Client ↔ [proxyPort] ↔ server. [rebind]
 * swaps the upstream (server-facing) socket for one with a new source port, so the server sees the
 * same connection arrive from a new 4-tuple. Each platform implements it over its native UDP API.
 */
interface RebindingProxy {
    /** The local port the client connects to (the proxy's client-facing socket). */
    val proxyPort: Int

    /** Swap the upstream socket for a fresh source port — the NAT rebind. */
    fun rebind()

    /** Stop the pump loops and release all sockets/resources. */
    suspend fun close()
}
