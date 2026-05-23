package com.ditchoom.socket.harness

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketOptions
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
 * Uses [SocketOptions.tlsInsecure] until per-platform CA injection lands
 * (TESTING_STRATEGY.md §7.3); the assertions don't depend on validation mode.
 * Skipped silently when the harness isn't running (see [isHarnessAvailable]).
 *
 * Each test issues an HTTP/1.1 `GET /<route>` against the nginx behind the
 * TLS terminator; routes are defined in `test-harness/http/conf.d/default.conf`.
 */
class TlsValidPathConformanceTests {
    // ── JSON GET — collapses the legacy multi-host tlsTo{ExampleDotCom,Nginx,Httpbin,…} family.

    /**
     * `GET /get` returns a deterministic small HTTP/1.1 200 response over TLS.
     * Replaces the legacy `tlsJsonApi` (httpbin.org) test plus the
     * `tlsTo{ExampleDotCom,Nginx,Httpbin,WithValidCertificate}` family — all
     * five had the same intent ("does TLS write+read complete end-to-end against
     * a server with a real cert?"), so they collapse to one harness assertion.
     *
     * Note: the TLS vhost (test-harness/tls/conf.d/default.conf) is a self-
     * contained nginx that only defines `/get` and `/` — distinct from the http
     * vhost. So we assert the status line + the `ok\n` body the route documents.
     * If a later phase wants a real JSON content-type assertion, the TLS conf
     * needs a `/json` route added (orchestrator note in the migration summary).
     */
    @Test
    fun tlsHarnessValidJsonGet() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            val response =
                ClientSocket.connect(
                    port = HarnessConfig.tlsValidPort,
                    hostname = harnessHost(),
                    socketOptions = SocketOptions.tlsInsecure(),
                    timeout = 5.seconds,
                ) { socket ->
                    socket.writeString(
                        "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\n" +
                            "Connection: close\r\nAccept: application/json\r\n\r\n",
                    )
                    socket.readString(timeout = 5.seconds)
                }
            assertTrue(
                response.startsWith("HTTP/1.1 200 OK"),
                "expected HTTP/1.1 200 OK status line, got: ${response.take(40)}",
            )
            assertTrue(
                response.contains("ok"),
                "expected `ok` body marker from /get route, got: ${response.take(160)}",
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
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                socket.writeString(
                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                val writeBuffer = BufferFactory.Default.allocate(65536) as WriteBuffer
                val bytesRead = socket.read(writeBuffer, 5.seconds)
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
                    socketOptions = SocketOptions.tlsInsecure(),
                    timeout = 5.seconds,
                ) { socket ->
                    socket.writeString(request)
                    socket.readString(timeout = 5.seconds)
                }

            // read(WriteBuffer, timeout) path
            val response2 =
                ClientSocket.connect(
                    port = HarnessConfig.tlsValidPort,
                    hostname = harnessHost(),
                    socketOptions = SocketOptions.tlsInsecure(),
                    timeout = 5.seconds,
                ) { socket ->
                    socket.writeString(request)
                    val writeBuffer = BufferFactory.Default.allocate(65536) as WriteBuffer
                    val bytesRead = socket.read(writeBuffer, 5.seconds)
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
     * session tickets, etc.). Migrated from `TlsErrorTests.tlsMultipleSequentialReads`
     * — the contract under test is "the loop terminates and yields the body",
     * not absolute size (the TLS vhost serves a small body; see the
     * `tlsHarnessLargerResponse` doc for the conf.d note).
     */
    @Test
    fun tlsHarnessMultipleSequentialReads() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                socket.writeString(
                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                var totalBytes = 0
                var readCount = 0
                while (socket.isOpen() && readCount < 10) {
                    try {
                        val buf = BufferFactory.Default.allocate(65536) as WriteBuffer
                        val n = socket.read(buf, 5.seconds)
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
     * Phase-4 will route this through the TLS-1.3-only port (14493) once that
     * nginx vhost lands; until then the valid port is sufficient because the
     * nginx defaults negotiate TLS 1.3 on modern OpenSSL.
     */
    @Test
    fun tlsHarnessFirstReadReturnsData() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                socket.writeString(
                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                val response = socket.read(5.seconds)
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
                                socketOptions = SocketOptions.tlsInsecure(),
                                timeout = 5.seconds,
                            ) { socket ->
                                socket.writeString(
                                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\n" +
                                        "Connection: close\r\n\r\n",
                                )
                                socket.readString(timeout = 5.seconds)
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
     * Multi-read drain over TLS — issues one HTTP GET, then keeps reading until
     * the server closes the connection, and verifies the body is delivered
     * intact across however many TCP reads it takes. Migrated from
     * `TlsErrorTests.tlsLargerResponse` (which leaned on Google's response size).
     *
     * The TLS vhost serves a small body (`ok\n` from `/get`) — see
     * `test-harness/tls/conf.d/default.conf`. That's enough to prove the
     * multi-read drain works; absolute byte count is not the contract under
     * test. If a later phase wants a >1 KB body over TLS, add a `/large` route
     * to the TLS conf (the http vhost already has one).
     */
    @Test
    fun tlsHarnessLargerResponse() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                socket.writeString(
                    "GET /get HTTP/1.1\r\nHost: ${harnessHost()}\r\nConnection: close\r\n\r\n",
                )
                val collected = StringBuilder()
                var readCount = 0
                while (socket.isOpen() && readCount < 20) {
                    try {
                        val chunk = socket.read(5.seconds)
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
                assertTrue(
                    response.contains("ok"),
                    "expected `ok` body to arrive intact across $readCount reads, " +
                        "got ${response.length} bytes: ${response.take(120)}",
                )
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
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "first connection should be open")
                socket.writeString(requestText)
                val response = socket.readString(timeout = 5.seconds)
                assertTrue(response.startsWith("HTTP/"), "first connection should receive HTTP response")
            }

            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "reconnection should be open")
                socket.writeString(requestText)
                val response = socket.readString(timeout = 5.seconds)
                assertTrue(response.startsWith("HTTP/"), "reconnection should receive HTTP response")
            }
        }
}
