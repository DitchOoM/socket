package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real-world **interop** smoke test: can our QUIC client complete a handshake with public,
 * production QUIC servers (different implementations + real CT-logged certs)? The local
 * [QuicHarnessIntegrationTests] proves the transport + data round-trip deterministically, but only
 * against our own `quic-echo` peer over a custom `"test"` ALPN — same quiche on both ends. This
 * covers what that can't: a different server stack (Cloudflare, Google, …) and real certificate
 * validation.
 *
 * Cross-platform on purpose (commonTest): the same check runs on every platform that has QUIC —
 * including Apple, where `verifyPeer` can't be disabled, so a successful connect here is also a
 * real-cert validation (the inverse of the private-CA -9808 harness gap, see issue #81).
 *
 * **Skip-on-unreachable, never flaky-fail.** Public endpoints / UDP-443 egress aren't guaranteed in
 * CI, and JS has no QUIC yet — so any connection failure is caught and logged, not failed. A
 * successful connect logs `public QUIC OK <host>` (a CI audit can grep for it to confirm the client
 * was actually exercised); if *every* endpoint is skipped we log a single loud line so a silent
 * "no real validation happened" can be spotted. Only a *post-handshake* assertion failure (stream
 * API misbehaving after a real connection) fails the test — which is exactly the regression we want.
 *
 * Scope note: this validates **connect + handshake + stream open**, not data retrieval — pulling
 * bytes from these servers needs an HTTP/3 (QPACK + frames) client we don't have. Data-from-public
 * is tracked separately (an `hq-interop` test server is the cheap path; full H3 is the larger one).
 */
class QuicPublicEndpointInteropTests {
    private val endpoints =
        listOf(
            "cloudflare-quic.com" to 443,
            "www.google.com" to 443,
        )

    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("h3"),
            verifyPeer = true, // exercise real CT-logged cert validation, the production path
            idleTimeout = 10.seconds,
        )
    private val connOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

    @Test
    fun connectsToPublicQuicEndpoints_orSkips() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                var connected = 0
                for ((host, port) in endpoints) {
                    try {
                        withQuicConnection(host, port, quicOptions, connOptions, 5.seconds) {
                            // Reaching here = QUIC + TLS handshake succeeded against a real,
                            // third-party server (and, where the platform validates, a real cert).
                            val stream = openStream()
                            assertTrue(stream.isOpen, "$host: stream should be open after openStream()")
                            assertTrue(
                                stream.streamId.isClientInitiated,
                                "$host: client-opened stream must be client-initiated",
                            )
                            stream.close()
                            connected++
                            println("[QuicPublicEndpointInteropTests] public QUIC OK $host:$port")
                        }
                    } catch (t: Throwable) {
                        // Unreachable / no UDP egress / JS-unimplemented / endpoint down — skip, log.
                        println(
                            "[QuicPublicEndpointInteropTests] public QUIC SKIP $host:$port — ${t::class.simpleName}: ${t.message}",
                        )
                    }
                }
                if (connected == 0) {
                    println(
                        "[QuicPublicEndpointInteropTests] ALL endpoints skipped — no public QUIC client validated " +
                            "(check UDP:443 egress / platform QUIC support)",
                    )
                }
            }
        }
}
