package com.ditchoom.socket

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guard for a v2 bug where the JVM TLS handler assigned an
 * [javax.net.ssl.SSLParameters] with `serverNames = null` back onto the
 * engine, wiping the implicit SNI populated by
 * `SSLContext.createSSLEngine(host, port)`. SNI-strict origins responded
 * with `unrecognized_name` / generic `handshake_failure`, surfacing as the
 * original `tlsToExampleDotCom` failure. The fix explicitly sets
 * `serverNames = listOf(SNIHostName(host))` before writing the parameters
 * back.
 *
 * Validates against three hosts known to be SNI-strict. Any one of these
 * failing means the engine is not sending an SNI extension.
 */
class SniStrictHostsTest {
    private val sniStrictHosts = listOf("www.example.com", "www.cloudflare.com", "badssl.com")

    // Deferred — SNI strictness migration blocked on separate server_name-only nginx vhosts (no default_server fallback) in test-harness/tls/conf.d/. Tracked in TODO.md.
    @Ignore
    @Test
    fun handshakeSucceedsAgainstSniStrictHosts() =
        runTestNoTimeSkipping {
            for (host in sniStrictHosts) {
                ClientSocket.connect(
                    port = 443,
                    hostname = host,
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen(), "TLS handshake should succeed against $host")
                    socket.writeString("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                    // Check the status line from raw bytes. The HTTP status line is
                    // ASCII, but decoding the full (UTF-8) body as a string is fragile
                    // when a multi-byte char straddles a TCP read boundary.
                    val firstChunk = socket.read(10.seconds)
                    val statusPrefix =
                        buildString {
                            repeat(minOf(5, firstChunk.remaining())) {
                                append(firstChunk.readByte().toInt().toChar())
                            }
                        }
                    assertTrue(
                        statusPrefix == "HTTP/",
                        "Expected HTTP response from $host; got status prefix: '$statusPrefix'",
                    )
                }
            }
        }
}
