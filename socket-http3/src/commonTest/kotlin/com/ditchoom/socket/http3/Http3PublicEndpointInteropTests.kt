package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
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
 * SKIP, not a failure, naming the phase it stopped in (dns, handshake, request, response body,
 * close; see [PublicEndpointAttempt]). An attempt that is still running [PublicEndpointAttempt.CANCEL_GRACE]
 * after its budget cancelled it is not the endpoint's doing, and fails the test with that phase. A successful GET logs `public H3 OK <host> status=<n>` (grep-able audit
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

    private val connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic())

    @Test
    fun getsFromPublicHttp3Endpoints_orSkips() =
        // Longer than every endpoint's budget plus its cancellation grace, so this test's own verdict
        // (which phase stalled, and whether it ignored cancellation) always lands before runTest's.
        runTest(timeout = (ENDPOINT_BUDGET + PublicEndpointAttempt.CANCEL_GRACE) * endpoints.size + 10.seconds) {
            withContext(Dispatchers.Default) {
                var validated = 0
                for ((host, port) in endpoints) {
                    // Connection + request failures (no egress, endpoint down, JS-unimplemented) skip,
                    // naming the phase; a returned status is asserted below. An attempt that does not
                    // unwind once cancelled fails: no wait in this library may ignore cancellation.
                    val outcome =
                        PublicEndpointAttempt.run("$host:$port", ENDPOINT_BUDGET) {
                            resolve(host)
                            enter("handshake")
                            withHttp3Connection(host, port, connectionOptions = connectionOptions, timeout = 8.seconds) {
                                enter("request")
                                val response = request(Http3Request(method = "GET", authority = host, path = "/"))
                                try {
                                    // Confirm the body is readable (Huffman-coded headers were decoded
                                    // to even parse :status; the body proves the DATA path too).
                                    enter("response body (status ${response.status})")
                                    response.readFullBody().freeIfNeeded()
                                    response.status
                                } finally {
                                    enter("close")
                                    response.close()
                                }
                            }
                        }
                    when (outcome) {
                        is PublicEndpointAttempt.Outcome.Done -> {
                            val status = outcome.value
                            assertTrue(
                                status in 100..599,
                                "$host: response :status should be a valid HTTP status code, got $status",
                            )
                            validated++
                            println("[Http3PublicEndpointInteropTests] public H3 OK $host:$port status=$status (${outcome.report})")
                        }
                        is PublicEndpointAttempt.Outcome.Unreachable ->
                            println("[Http3PublicEndpointInteropTests] public H3 SKIP ${outcome.report}")
                        is PublicEndpointAttempt.Outcome.IgnoredCancellation -> fail(outcome.report)
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

    private companion object {
        val ENDPOINT_BUDGET = 20.seconds
    }
}
