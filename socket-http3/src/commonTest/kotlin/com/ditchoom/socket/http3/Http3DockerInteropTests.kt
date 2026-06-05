package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * **QPACK eviction interop** against an INDEPENDENT HTTP/3 implementation (aioquic / lsqpack) running
 * in a local Docker container (`socket-http3/docker-interop/`, started via `run-server.sh`).
 *
 * Why a third party: the in-process loopback ([Http3LoopbackTestSuite]) round-trips our encoder through
 * *our own* decoder, so a bug symmetric across both halves could hide. A foreign decoder (lsqpack) can't
 * be wrong in the same way — if our encoder's dynamic-table **eviction** accounting drifts from the wire
 * contract (RFC 9204 §2.1.3), lsqpack raises a decompression / decoder-stream error and the connection
 * dies, failing this test. Running on loopback also avoids the UDP/443 egress flakiness that makes
 * [Http3PublicEndpointInteropTests] skip on WSL2/CI.
 *
 * The driver issues many requests on ONE connection, each carrying a distinct large custom header, to
 * push the encoder's dynamic table past capacity so it evicts and then re-references / re-inserts entries
 * — exactly the churn the eviction tune enables. With the default 4096-octet table and ~220-octet
 * entries, the table fills after ~18 entries, so 64 requests force dozens of evictions. The server echoes
 * each `x-*` request header back as `echo-x-*`; the test asserts every echo matches, proving lsqpack
 * decoded our (evicting) encoder stream correctly the whole way through.
 *
 * **Skip-on-unreachable, never flaky-fail.** If the Docker server is not up on 127.0.0.1:4433 the
 * handshake fails before [connected] flips and the test logs a SKIP. A failure *after* the connection is
 * established (a wrong echo, or a QPACK-triggered connection close) re-throws — that is a real regression.
 */
class Http3DockerInteropTests {
    private val host = "127.0.0.1"
    private val port = 4433

    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false, // the local server uses a self-signed cert; we are testing QPACK, not PKI
            idleTimeout = 20.seconds,
        )

    // Zero-copy stream I/O reads each buffer's native address ⇒ native-memory factory on K/N.
    private val connectionOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

    @Test
    fun evictingEncoderRoundTripsAgainstAioquic_orSkips() =
        runTest(timeout = 90.seconds) {
            withContext(Dispatchers.Default) {
                var connected = false
                try {
                    withHttp3Connection(host, port, quicOptions, connectionOptions, timeout = 8.seconds) {
                        // Let the server SETTINGS (its QPACK_MAX_TABLE_CAPACITY) arrive so our encoder
                        // activates its dynamic table before we start issuing requests.
                        withTimeout(6.seconds) { peerSettings() }
                        connected = true // past the handshake — failures below are real regressions

                        val recurring = QpackHeaderField("x-recurring", "stable-token-kept-across-every-request")
                        val total = 64
                        for (i in 0 until total) {
                            // ~180-octet value ⇒ entry ≈ name+value+32 ≈ 220 octets; the 4096 table holds
                            // ~18 of these, so by request ~18 the encoder must evict to keep inserting.
                            val value = "x".repeat(180) + "-$i"
                            val fields =
                                mutableListOf(
                                    QpackHeaderField("x-evict-$i", value),
                                    recurring, // re-referenced every request: pinned while in-flight, ages toward eviction
                                )
                            // Periodically re-send a header old enough to have been evicted: the encoder
                            // must treat it as brand-new (re-insert), and lsqpack must still decode it.
                            if (i >= 24 && i % 4 == 0) {
                                val old = i - 22
                                fields += QpackHeaderField("x-evict-$old", "x".repeat(180) + "-$old")
                            }

                            val response = request(Http3Request("GET", host, "/item-$i", headers = fields))
                            try {
                                assertEquals(200, response.status, "request $i status")
                                assertEquals(
                                    value,
                                    response.headers.firstOrNull { it.name == "echo-x-evict-$i" }?.value,
                                    "request $i: server (lsqpack) must decode our evicting encoder's x-evict-$i",
                                )
                                assertEquals(
                                    recurring.value,
                                    response.headers.firstOrNull { it.name == "echo-x-recurring" }?.value,
                                    "request $i: x-recurring must survive table churn",
                                )
                                response.readFullBody().freeIfNeeded()
                            } finally {
                                response.close()
                            }
                        }
                        assertNull(connectionError, "eviction churn must not raise a QPACK connection error")
                        println("[Http3DockerInteropTests] OK — $total evicting requests round-tripped through aioquic/lsqpack")
                    }
                } catch (t: Throwable) {
                    if (connected) throw t // a post-handshake failure is a real eviction/QPACK regression
                    println(
                        "[Http3DockerInteropTests] SKIP $host:$port — ${t::class.simpleName}: ${t.message} " +
                            "(start the server: socket-http3/docker-interop/run-server.sh)",
                    )
                }
            }
        }
}
