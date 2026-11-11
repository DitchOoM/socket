@file:OptIn(ExperimentalEncodingApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [QuicOptions.serverCertVerifiers], against a real server on every quiche backend:
 *
 *  - the verifier is handed the server's **whole** chain, leaf first, as the server sent it;
 *  - a rejection fails the connect with a typed [ServerCertificateRejectedException] **before** the
 *    caller's block runs;
 *  - it is **additive**: a chain the platform's own validation refuses never reaches the verifier, so a
 *    verifier that answers Trusted cannot widen what is trusted.
 *
 * Members point at `socket-quic-quiche/testcerts/two-cert-chain.crt` (`cert.crt`, then a second
 * self-signed certificate), served with `cert.key`, and hand over its text; the suite decodes the expected
 * DER from that PEM itself, independently of the code under test.
 */
abstract class ServerCertVerifierTestSuite {
    /** A server presenting the two certificates of `two-cert-chain.crt` as its chain. */
    abstract fun twoCertificateChainTlsConfig(): QuicTlsConfig

    /** The text of `two-cert-chain.crt`. */
    abstract fun twoCertificateChainPem(): String

    // Split by markers rather than a regex: DOT_MATCHES_ALL is JVM-only, and this compiles for every target.
    private fun expectedChainDer(): List<ReadBuffer> =
        twoCertificateChainPem()
            .split("-----BEGIN CERTIFICATE-----")
            .drop(1)
            .map { block ->
                val base64 = block.substringBefore("-----END CERTIFICATE-----").filterNot { it.isWhitespace() }
                val der = Base64.Mime.decode(base64) // ByteArray — test code
                BufferFactory.deterministic().allocate(der.size).apply {
                    der.forEach { writeByte(it) }
                    resetForRead()
                }
            }

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private fun options() = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    @Test
    fun theVerifierSeesTheWholeChainLeafFirst() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = twoCertificateChainTlsConfig(), quicOptions = options()) {
                    val serverJob = launch { runCatching { connections {} } }
                    try {
                        val expected = expectedChainDer()
                        var seenCount = -1
                        var seenMatches: List<Boolean> = emptyList()
                        var seenName: String? = null
                        val verifying =
                            options().copy(
                                serverCertVerifiers =
                                    listOf(
                                        ServerCertVerifier { chain ->
                                            seenName = chain.serverName
                                            seenCount = chain.certificates.size
                                            seenMatches =
                                                chain.certificates.zip(expected) { got, want -> got.slice().contentEquals(want.slice()) }
                                            ServerCertVerdict.Trusted
                                        },
                                    ),
                            )
                        var blockRan = false
                        withQuicConnection("127.0.0.1", port, verifying, timeout = 10.seconds.scaled) { blockRan = true }
                        assertTrue(blockRan, "a Trusted verdict hands the connection to the caller")
                        assertEquals("127.0.0.1", seenName, "the verifier is told the name the connection asked for")
                        assertEquals(2, seenCount, "the whole chain, not just the leaf")
                        assertEquals(listOf(true, true), seenMatches, "leaf first, each byte-identical to the chain file")
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun aRejectionFailsTheConnectBeforeTheBlockRuns() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = twoCertificateChainTlsConfig(), quicOptions = options()) {
                    val serverJob = launch { runCatching { connections {} } }
                    try {
                        var blockRan = false
                        val rejecting =
                            options().copy(serverCertVerifiers = listOf(ServerCertVerifier { ServerCertVerdict.Rejected("not our key") }))
                        val ex =
                            assertFailsWith<ServerCertificateRejectedException> {
                                withQuicConnection("127.0.0.1", port, rejecting, timeout = 10.seconds.scaled) { blockRan = true }
                            }
                        assertEquals(ServerCertificateRejection.RejectedByVerifier("not our key"), ex.rejection)
                        assertFalse(blockRan, "a rejected server is never handed to the caller")
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun aChainThePlatformRefusesNeverReachesTheVerifier() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = twoCertificateChainTlsConfig(), quicOptions = options()) {
                    val serverJob = launch { runCatching { connections {} } }
                    try {
                        var consulted = false
                        // verifyPeer on, default anchors: the self-signed test chain is not trusted, and a
                        // verifier that would trust anything must not get the chance to say so.
                        val additive =
                            options().copy(
                                verifyPeer = true,
                                serverCertVerifiers =
                                    listOf(
                                        ServerCertVerifier {
                                            consulted = true
                                            ServerCertVerdict.Trusted
                                        },
                                    ),
                            )
                        val failure =
                            runCatching {
                                withQuicConnection("127.0.0.1", port, additive, timeout = 10.seconds.scaled) {}
                            }.exceptionOrNull()
                        assertIs<Throwable>(failure, "the platform's own validation refuses a self-signed chain")
                        assertFalse(consulted, "the verifier narrows trust; it is never asked about a chain the platform refused")
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The post-handshake chain check is part of the handshake, so it spends the handshake's budget: a
     * verifier that never answers ends the connect with the same typed `HandshakeTimeout` an unanswered
     * handshake does, rather than leaving a connect that has no outer bound waiting for it forever. The
     * platform trust store (iOS SecTrust) is consulted from the same place, so it is bound the same way.
     */
    @Test
    fun aVerifierThatNeverAnswersEndsTheConnectAtTheHandshakeBudget() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = twoCertificateChainTlsConfig(), quicOptions = options()) {
                    val serverJob = launch { runCatching { connections {} } }
                    try {
                        val silent = options().copy(serverCertVerifiers = listOf(ServerCertVerifier { awaitCancellation() }))
                        val budget = 3.seconds.scaled
                        // The engine's own connect, which has no bound but the one it is handed; withQuicConnection
                        // adds an outer deadline that would end this wait whatever the engine did.
                        val outcome =
                            withTimeoutOrNull(budget + 10.seconds.scaled) {
                                runCatching {
                                    defaultQuicEngine.connect(
                                        QuicClientBinding.OwnSocket,
                                        QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                                        "127.0.0.1",
                                        silent,
                                        TransportConfig(),
                                        budget,
                                    )
                                }.exceptionOrNull()
                            }
                        assertTrue(outcome != null, "the connect was still waiting on the verifier 10 s after its $budget budget")
                        val failure = assertIs<QuicCloseException>(outcome)
                        assertEquals(QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(budget)), failure.closeReason)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
