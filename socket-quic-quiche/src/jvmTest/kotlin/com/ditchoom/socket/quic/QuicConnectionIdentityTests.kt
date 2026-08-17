package com.ditchoom.socket.quic

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [QuicConnection.identity] against a real quiche connection.
 *
 * The property that matters is **not** that an id exists — it is that two connections alive at the
 * same moment can be told apart. That is the exact question consumer issue #1178 could not answer:
 * three clients re-authenticating in lockstep every 8.4s, where wall-clock timestamps cannot say which
 * log line belongs to which client because they all die inside the same second.
 *
 * Also pins that the session id is *stable* per connection, since an id that changed under you would
 * be worse than none — you would correlate two halves of one connection as two different ones.
 */
class QuicConnectionIdentityTests {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun concurrentConnectionsHaveDistinctStableSessionIds() =
        runQuicTest(timeout = 30.seconds) {
            skipOnMissingNativeLib(QuicConnectionIdentityTests::class) {
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                    val serverJob = launch { connections { acceptStream() } }
                    try {
                        withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                            val first = identity

                            // Non-empty, and read twice with the same answer: a session id that drifted
                            // would split one connection across two identities in a log.
                            assertTrue(
                                first.session.hex.isNotBlank(),
                                "session id was blank — a connection that cannot name itself is exactly " +
                                    "what made #1178 unanswerable",
                            )
                            assertEquals(
                                first.session,
                                identity.session,
                                "session id changed between reads; it is documented as stable for the " +
                                    "connection's whole life, which is the only reason it is usable for " +
                                    "correlation across a reconnect cycle",
                            )

                            // The wire CID is backend-dependent and this pins which backends bind it.
                            // FFM and both cinterops call quiche_conn_source_id; the JNI backend does
                            // not (that needs a C shim entry), so it truthfully reports Unavailable —
                            // which is the whole reason the type is sealed rather than a nullable or an
                            // empty string. Whichever backend answers here, a Known value must be real:
                            // a blank hex would be a fabricated id wearing the Known label.
                            when (val wire = first.wire) {
                                is QuicWireConnectionId.Known ->
                                    assertTrue(
                                        wire.hex.isNotBlank(),
                                        "wire CID reported Known with a blank value — Known must mean the " +
                                            "backend actually read a CID, not that it returned nothing",
                                    )
                                QuicWireConnectionId.Unavailable -> Unit // JNI: accessor not bound, stated honestly
                            }

                            // The second connection is the point: two live connections, two identities.
                            withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                                assertNotEquals(
                                    first.session,
                                    identity.session,
                                    "two concurrent connections reported the same session id — they " +
                                        "cannot be told apart in a log, which is the whole failure mode " +
                                        "this identity exists to remove",
                                )
                            }
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
