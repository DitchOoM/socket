@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.quic.CertificateHashVerification
import com.ditchoom.socket.quic.PeerCertificate
import com.ditchoom.socket.quic.PeerCertificateSupport
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheEngine
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.currentThreadName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A core client checks the server's certificate after the handshake and before the connect returns, as the 4.x engine
 * does: a leaf-hash pin, a [ServerCertVerifier], and RequireBoth. A refused server ends with the typed failure 4.x
 * raises and a CONNECTION_CLOSE carrying the TLS alert; a verifier never runs on the loop thread.
 */
class CorePeerVerificationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aClientPinnedToAnotherCertificateFailsTypedAndSendsBadCertificate() =
        coreTest("CorePeerVerificationTests.aClientPinnedToAnotherCertificateFailsTypedAndSendsBadCertificate") {
            mint().use { pinned ->
                mint().use { served ->
                    val ended = CompletableDeferred<QuicCloseReason>()
                    withMintedCoreServer(served, onConnection = { connection ->
                        val closed = connection.state.first { it is QuicConnectionState.Closed } as QuicConnectionState.Closed
                        ended.complete(closed.reason)
                    }) { port ->
                        val failure =
                            assertFailsWith<CertificateHashPinningException> {
                                connectCore(
                                    port,
                                    loops = loops,
                                    options = coreTestOptions.copy(serverCertificateHashes = listOf(pinned.hash)),
                                )
                            }
                        val mismatch = assertIs<CertificateHashPinningFailure.HashMismatch>(failure.failure)
                        assertEquals(1, mismatch.pinnedCount)
                        assertTrue(mismatch.computedLeafHash.startsWith("sha-256:"), mismatch.computedLeafHash)
                        assertEquals<QuicCloseReason>(
                            QuicCloseReason.ByPeer(QuicError.CryptoError(BAD_CERTIFICATE)),
                            withTimeout(10.seconds) { ended.await() },
                            "the server is told why: CONNECTION_CLOSE with the bad_certificate alert",
                        )
                    }
                }
            }
        }

    @Test
    fun aClientPinnedToTheServedCertificateConnects() =
        coreTest("CorePeerVerificationTests.aClientPinnedToTheServedCertificateConnects") {
            mint().use { served ->
                withMintedCoreServer(served, onConnection = { awaitCancellation() }) { port ->
                    val client =
                        connectCore(port, loops = loops, options = coreTestOptions.copy(serverCertificateHashes = listOf(served.hash)))
                    assertIs<EdgeEnd.Open>(client.connection.end(), "a matching pin hands over an open connection")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aRejectingVerifierFailsTheConnectOffTheLoopThread() =
        coreTest("CorePeerVerificationTests.aRejectingVerifierFailsTheConnectOffTheLoopThread") {
            mint().use { served ->
                var verifiedOn = ""
                var leafMatched = false
                val rejecting =
                    ServerCertVerifier { chain ->
                        verifiedOn = currentThreadName()
                        leafMatched = bytesOf(chain.leaf).contentEquals(bytesOf(served.der()))
                        ServerCertVerdict.Rejected("not our key")
                    }
                withMintedCoreServer(served, onConnection = { awaitCancellation() }) { port ->
                    val failure =
                        assertFailsWith<ServerCertificateRejectedException> {
                            connectCore(port, loops = loops, options = coreTestOptions.copy(serverCertVerifiers = listOf(rejecting)))
                        }
                    assertEquals<ServerCertificateRejection>(
                        ServerCertificateRejection.RejectedByVerifier("not our key"),
                        failure.rejection,
                    )
                    assertTrue(leafMatched, "the verifier is handed the served leaf")
                    assertTrue(verifiedOn.isNotEmpty(), "the verifier ran")
                    assertFalse(verifiedOn.startsWith(LOOP_THREAD), "a verifier is user code and never runs on the loop: $verifiedOn")
                }
            }
        }

    @Test
    fun aTrustingVerifierHandsOverTheConnection() =
        coreTest("CorePeerVerificationTests.aTrustingVerifierHandsOverTheConnection") {
            mint().use { served ->
                var consulted = 0
                val trusting =
                    ServerCertVerifier { chain ->
                        consulted++
                        assertContentEquals(bytesOf(served.der()), bytesOf(chain.leaf))
                        ServerCertVerdict.Trusted
                    }
                withMintedCoreServer(served, onConnection = { awaitCancellation() }) { port ->
                    val client = connectCore(port, loops = loops, options = coreTestOptions.copy(serverCertVerifiers = listOf(trusting)))
                    assertEquals(1, consulted, "the verifier is consulted once, before the connect returns")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun requireBothRefusesASelfSignedCertificateEvenWhenItsHashMatches() =
        coreTest("CorePeerVerificationTests.requireBothRefusesASelfSignedCertificateEvenWhenItsHashMatches") {
            mint().use { served ->
                withMintedCoreServer(served, onConnection = { awaitCancellation() }) { port ->
                    val requireBoth =
                        coreTestOptions.copy(
                            serverCertificateHashes = listOf(served.hash),
                            certificateHashVerification = CertificateHashVerification.RequireBoth,
                        )
                    val outcome = runCatching { connectCore(port, loops = loops, options = requireBoth) }
                    outcome.onSuccess {
                        it.connection.close(QuicError.NoError)
                        fail("RequireBoth accepted a self-signed certificate on a hash match alone")
                    }
                }
            }
        }

    private companion object {
        /** TLS `bad_certificate` (RFC 8446 §6.2). */
        const val BAD_CERTIFICATE = 42
    }
}

/** A fresh W3C-conformant self-signed certificate. */
internal suspend fun mint(): PeerCertificate =
    when (val support = QuicheEngine.peerCertificates) {
        is PeerCertificateSupport.Available -> support.mint()
        PeerCertificateSupport.Unavailable -> fail("the quiche engine mints peer certificates on every platform the core runs on")
    }

internal fun bytesOf(buffer: com.ditchoom.buffer.ReadBuffer): ByteArray {
    val view = buffer.slice()
    return view.readByteArray(view.remaining())
}

/**
 * Serves [certificate] from a core server for the length of [body], which gets its port. The certificate's key exists
 * as files only while the core server loads it.
 */
internal suspend fun withMintedCoreServer(
    certificate: PeerCertificate,
    onConnection: suspend kotlinx.coroutines.CoroutineScope.(CoreConnection) -> Unit,
    body: suspend (port: Int) -> Unit,
) {
    certificate.withPemFiles { tls -> withCoreServer(tls = tls, onConnection = onConnection) { server -> body(server.port) } }
}
