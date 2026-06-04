package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real-world **HTTP/3 interop** smoke test: can our client issue a `GET /` to production `h3`
 * servers (Cloudflare, Google — different server stacks, real CT-logged certs) and read back a
 * decoded response? This is the end-to-end exercise of the whole stack — QUIC handshake +
 * [withHttp3Connection] bootstrap (control/QPACK streams + SETTINGS) + [Http3Connection.request]
 * (QPACK static-table HEADERS + half-close) + response decode (QPACK incl. Huffman, since these
 * servers Huffman-code response headers) — that the scripted unit tests can't cover.
 *
 * **Skip-on-unreachable, never flaky-fail.** Public endpoints / UDP-443 egress aren't guaranteed
 * in CI, and JS has no QUIC yet — so any *connection/request* failure is caught and logged as a
 * SKIP, not a failure. A successful GET logs `public H3 OK <host> status=<n>` (grep-able audit
 * marker). The `:status` assertion runs **outside** the skip-catch: a connection that succeeds
 * but yields a nonsense status is a real regression and fails the test. If every endpoint skips,
 * a single loud line flags that no real H3 validation happened.
 */
class Http3PublicEndpointInteropTests {
    private val endpoints =
        listOf(
            "cloudflare-quic.com" to 443,
            "www.google.com" to 443,
        )

    private val connectionOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

    @Test
    fun getsFromPublicHttp3Endpoints_orSkips() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                var validated = 0
                for ((host, port) in endpoints) {
                    // Connection + request failures (no egress, endpoint down, JS-unimplemented)
                    // skip; a returned status is asserted below, outside the catch.
                    val status: Int? =
                        try {
                            withHttp3Connection(host, port, connectionOptions = connectionOptions, timeout = 8.seconds) {
                                val response = request(Http3Request(method = "GET", authority = host, path = "/"))
                                try {
                                    // Confirm the body is readable (Huffman-coded headers were decoded
                                    // to even parse :status; the body proves the DATA path too).
                                    response.readFullBody().freeIfNeeded()
                                    response.status
                                } finally {
                                    response.close()
                                }
                            }
                        } catch (t: Throwable) {
                            println(
                                "[Http3PublicEndpointInteropTests] public H3 SKIP $host:$port — " +
                                    "${t::class.simpleName}: ${t.message}",
                            )
                            null
                        }

                    if (status != null) {
                        assertTrue(
                            status in 100..599,
                            "$host: response :status should be a valid HTTP status code, got $status",
                        )
                        validated++
                        println("[Http3PublicEndpointInteropTests] public H3 OK $host:$port status=$status")
                    }
                }
                if (validated == 0) {
                    println(
                        "[Http3PublicEndpointInteropTests] ALL endpoints skipped — no public H3 GET validated " +
                            "(check UDP:443 egress / platform QUIC support)",
                    )
                }
            }
        }
}
