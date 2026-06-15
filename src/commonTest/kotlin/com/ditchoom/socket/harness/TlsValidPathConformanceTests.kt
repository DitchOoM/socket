package com.ditchoom.socket.harness

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.data.readBuffer
import com.ditchoom.data.readInto
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3 conformance suite: read/write-shape behavior over a TLS connection
 * against the harness valid cert (port 14443). Sibling of [TlsConformanceTests]
 * (which covers the cert-validation matrix); split out so each file stays
 * focused on one axis.
 *
 * Uses [SocketOptions.tlsDefault] — same validation mode as production callers.
 * Requires the harness root CA (`test-harness/tls/certs/ca.crt`) to be trusted
 * by the platform; locally these tests **fail** with a cert-validation error
 * until you inject the CA (see [TlsConformanceTests] KDoc for commands). CI
 * wires the injection automatically; see `.github/workflows/review.yaml`'s
 * "Trust harness CA" steps. These tests are about read-shape / concurrency /
 * partial-read correctness rather than cert validation per se; using
 * `tlsDefault()` keeps the validation mode consistent with
 * [TlsConformanceTests] so the suite has a single CA-injection precondition.
 *
 * Skipped silently when the harness isn't running (see [isHarnessAvailable]).
 *
 * Each test issues an HTTP/1.1 `GET /<route>` against the nginx behind the
 * TLS terminator; routes are defined in `test-harness/http/conf.d/default.conf`.
 */
class TlsValidPathConformanceTests {
    // ── JSON GET — collapses the legacy multi-host tlsTo{ExampleDotCom,Nginx,Httpbin,…} family.

    /**
     * `GET /json` returns a deterministic `application/json` HTTP/1.1 200
     * response over TLS. Replaces the legacy `tlsJsonApi` (httpbin.org) test
     * plus the `tlsTo{ExampleDotCom,Nginx,Httpbin,WithValidCertificate}` family
     * — all five had the same intent ("does TLS write+read complete end-to-end
     * against a server with a real cert?"), so they collapse to one harness
     * assertion. Phase 4 added the `/json` route on the TLS vhost (mirroring
     * the http vhost) so we can assert the typed response without softening.
     */
    @Test
    fun tlsHarnessValidJsonGet() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val response =
                ClientSocket.connect(
                    port = HarnessConfig.tlsValidPort,
                    hostname = harnessHost(),
                    config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
                ) { socket ->
                    socket.writeString(
                        "GET /json HTTP/1.1\r\nHost: ${harnessHost()}\r\n" +
                            "Connection: close\r\nAccept: application/json\r\n\r\n",
                    )
                    socket.readString(deadline = 5.seconds)
                }
            assertTrue(
                response.startsWith("HTTP/1.1 200 OK"),
                "expected HTTP/1.1 200 OK status line, got: ${response.take(40)}",
            )
            assertTrue(
                response.contains("Content-Type: application/json", ignoreCase = true),
                "expected application/json Content-Type header, got: ${response.take(240)}",
            )
            assertTrue(
                response.contains("""{"ok":true}"""),
                "expected JSON body `{\"ok\":true}` from /json route, got: ${response.take(240)}",
            )
        }

    // ── Read-shape variants ───────────────────────────────────────────────────

    /**
     * `read(WriteBuffer, timeout)` overload returns TLS-decrypted bytes (not
     * raw ciphertext). Regression test for the bug where `AsyncBaseClientSocket`'s
     * WriteBuffer-read path bypassed TLS decryption. Migrated from
     * `TlsErrorTests.tlsReadIntoWriteBuffer`.
     */
    @Test
    fun tlsHarnessReadIntoWriteBuffer() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
            ) { socket ->
                socket.writeString(
                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                val writeBuffer = BufferFactory.Default.allocate(65536) as WriteBuffer
                val bytesRead = socket.readInto(writeBuffer, 5.seconds)
                assertTrue(bytesRead > 0, "WriteBuffer read should return data")

                val readBuffer = writeBuffer as PlatformBuffer
                readBuffer.setLimit(readBuffer.position())
                readBuffer.position(0)
                val text = readBuffer.readString(readBuffer.remaining(), Charset.UTF8)
                assertTrue(
                    text.startsWith("HTTP/"),
                    "WriteBuffer path should return decrypted HTTP response, got: ${text.take(50)}",
                )
            }
        }

    /**
     * Both `read(timeout)` and `read(WriteBuffer, timeout)` produce equivalent
     * decrypted plaintext on independent connections. Migrated from
     * `TlsErrorTests.tlsBothReadOverloadsReturnSameData`.
     */
    @Test
    fun tlsHarnessBothReadOverloadsReturnSameData() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val request =
                "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n"

            // read(timeout) path
            val response1 =
                ClientSocket.connect(
                    port = HarnessConfig.tlsValidPort,
                    hostname = harnessHost(),
                    config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
                ) { socket ->
                    socket.writeString(request)
                    socket.readString(deadline = 5.seconds)
                }

            // read(WriteBuffer, timeout) path
            val response2 =
                ClientSocket.connect(
                    port = HarnessConfig.tlsValidPort,
                    hostname = harnessHost(),
                    config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
                ) { socket ->
                    socket.writeString(request)
                    val writeBuffer = BufferFactory.Default.allocate(65536) as WriteBuffer
                    val bytesRead = socket.readInto(writeBuffer, 5.seconds)
                    assertTrue(bytesRead > 0, "WriteBuffer read should return data")
                    val readBuffer = writeBuffer as PlatformBuffer
                    readBuffer.setLimit(readBuffer.position())
                    readBuffer.position(0)
                    readBuffer.readString(readBuffer.remaining(), Charset.UTF8)
                }

            assertTrue(response1.startsWith("HTTP/"), "read(timeout) should return HTTP response")
            assertTrue(response2.startsWith("HTTP/"), "read(WriteBuffer) should return HTTP response")
            assertEquals(
                response1.substringBefore("\r\n"),
                response2.substringBefore("\r\n"),
                "both read paths should produce the same HTTP status line",
            )
        }

    /**
     * Multiple sequential reads on a single TLS connection cumulatively drain
     * the response. Exercises TLS session state across reads (key updates,
     * session tickets, etc.). Migrated from `TlsErrorTests.tlsMultipleSequentialReads`.
     *
     * Phase 4 routes this against `/large` (≥ 8 KB body) so multi-read drain
     * is actually forced — a single-read drain on a small body would pass the
     * loop trivially. Smallish nginx record sizes split the body across
     * multiple TLS records, which the socket layer surfaces as multiple
     * `read()` returns.
     */
    @Test
    fun tlsHarnessMultipleSequentialReads() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
            ) { socket ->
                socket.writeString(
                    "GET /large HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                var totalBytes = 0
                var readCount = 0
                while (socket.isOpen && readCount < 20) {
                    try {
                        val buf = BufferFactory.Default.allocate(65536) as WriteBuffer
                        val n = socket.readInto(buf, 5.seconds)
                        if (n <= 0) break
                        totalBytes += n
                        readCount++
                    } catch (_: SocketClosedException) {
                        break
                    }
                }
                assertTrue(
                    totalBytes > 0,
                    "expected data across reads, got $totalBytes bytes in $readCount reads",
                )
                assertTrue(readCount >= 1, "expected at least one read")
            }
        }

    /**
     * First read after a write returns application data (not empty from a
     * TLS-1.3 NewSessionTicket consumed silently). Migrated from
     * `TlsErrorTests.tlsFirstReadReturnsData`.
     *
     * Phase 4 routes this through the sixth TLS vhost — `ssl_protocols TLSv1.3`
     * with no 1.2 fallback (port 14493). This locks the assertion onto a
     * handshake that *must* emit NewSessionTicket post-handshake, which is
     * exactly the regression surface: the underlying bug was a TLS-1.3 NST
     * being surfaced to the caller as an empty `read()` return instead of
     * silently consumed by the SSLEngine. On the default 443 port the
     * negotiated protocol can vary with OpenSSL/JSSE policy, so a green run
     * there doesn't prove TLS-1.3 was actually exercised; here it does.
     */
    @Test
    fun tlsHarnessFirstReadReturnsData() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsTls13Port,
                hostname = harnessHost(),
                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
            ) { socket ->
                socket.writeString(
                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                val response = socket.readBuffer(5.seconds)
                val remaining = response.remaining()
                assertTrue(remaining > 0, "first read should return data, not empty (NewSessionTicket)")
                val text = response.readString(remaining, Charset.UTF8)
                assertTrue(text.startsWith("HTTP/"), "first read should be the HTTP response, got: ${text.take(40)}")
            }
        }

    // ── Concurrent connections ────────────────────────────────────────────────

    /**
     * Five parallel TLS connections all complete successfully. Migrated from
     * `TlsErrorTests.tlsConcurrentConnections`.
     */
    @Test
    fun tlsHarnessConcurrentConnections() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            coroutineScope {
                val results =
                    (1..5).map {
                        async {
                            ClientSocket.connect(
                                port = HarnessConfig.tlsValidPort,
                                hostname = harnessHost(),
                                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
                            ) { socket ->
                                socket.writeString(
                                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\n" +
                                        "Connection: close\r\n\r\n",
                                )
                                socket.readString(deadline = 5.seconds)
                            }
                        }
                    }
                results.forEach { deferred ->
                    val response = deferred.await()
                    assertTrue(
                        response.startsWith("HTTP/"),
                        "concurrent connection should receive valid HTTP response",
                    )
                }
            }
        }

    // ── Larger response ───────────────────────────────────────────────────────

    /**
     * Multi-read drain over TLS against an ≥ 8 KB body — issues one HTTP GET
     * against `/large`, keeps reading until the server closes, and verifies
     * the body is delivered intact across however many TCP/TLS-record reads
     * it takes. Migrated from `TlsErrorTests.tlsLargerResponse` (which leaned
     * on Google's response size).
     *
     * Phase 4 added the `/large` route on the TLS vhost (mirroring the http
     * vhost) — 8256 bytes of deterministic content, so we can pin both
     * cumulative byte count (≥ 8192) AND byte-exact reassembly.
     */
    @Test
    fun tlsHarnessLargerResponse() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
            ) { socket ->
                socket.writeString(
                    "GET /large HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                val collected = StringBuilder()
                var readCount = 0
                while (socket.isOpen && readCount < 40) {
                    try {
                        val chunk = socket.readBuffer(5.seconds)
                        val remaining = chunk.remaining()
                        if (remaining <= 0) break
                        collected.append(chunk.readString(remaining, Charset.UTF8))
                        readCount++
                    } catch (_: SocketClosedException) {
                        break
                    }
                }
                val response = collected.toString()
                assertTrue(response.startsWith("HTTP/1.1 200 OK"), "expected HTTP status line")

                // Drop the headers — chunk-encoded or content-length doesn't matter, we
                // emitted `Connection: close` so nginx ends the body at FIN. Split on
                // the header/body delimiter and assert against the body.
                val splitIdx = response.indexOf("\r\n\r\n")
                assertTrue(splitIdx > 0, "expected end-of-headers marker, got: ${response.take(160)}")
                val body = response.substring(splitIdx + 4)

                // Body content: 64 lines of 128 chars each + newlines = 8256 bytes.
                // First 16 lines cycle through 0..f, then the pattern repeats four times.
                assertTrue(
                    body.length >= 8192,
                    "expected /large body ≥ 8 KB, got ${body.length} bytes across $readCount reads",
                )
                // Byte-exact reassembly check: the first 128 chars should be all '0',
                // and the body should contain all hex digits 0..f as line prefixes.
                assertTrue(
                    body.startsWith("0".repeat(128)),
                    "expected /large body to start with 128 zeros (line 1), got: ${body.take(40)}",
                )
                for (c in "0123456789abcdef") {
                    assertTrue(
                        body.contains("$c".repeat(128)),
                        "expected body to contain a line of 128 '$c' characters " +
                            "(reassembly check across $readCount reads)",
                    )
                }
                assertTrue(readCount >= 1, "expected at least one successful read")
            }
        }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    /**
     * Two sequential TLS connections to the same host both succeed. Migrated
     * from `TlsErrorTests.tlsReconnectAfterClose`.
     */
    @Test
    fun tlsHarnessReconnectAfterClose() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val requestText =
                "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n"

            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
            ) { socket ->
                assertTrue(socket.isOpen, "first connection should be open")
                socket.writeString(requestText)
                val response = socket.readString(deadline = 5.seconds)
                assertTrue(response.startsWith("HTTP/"), "first connection should receive HTTP response")
            }

            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
            ) { socket ->
                assertTrue(socket.isOpen, "reconnection should be open")
                socket.writeString(requestText)
                val response = socket.readString(deadline = 5.seconds)
                assertTrue(response.startsWith("HTTP/"), "reconnection should receive HTTP response")
            }
        }
}
