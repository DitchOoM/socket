package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
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
        val resp = read(readTimeout)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
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
                                        stream.write(data.buffer, 5.seconds)
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
