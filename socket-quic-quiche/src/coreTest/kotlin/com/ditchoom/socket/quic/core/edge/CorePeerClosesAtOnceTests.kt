@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.quic.PlatformChainTrust
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.platformChainTrust
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A server that closes the moment its handshake completes, over a real loopback socket on every platform: the connect
 * returns the connection whether the close arrived before or after it, its first operation reports the peer's close,
 * and what the handshake settled stays readable. The server is checked against the certificates the handshake copied,
 * however long the check takes: a pinned or trusted one is returned, one that is not trusted still fails the connect,
 * and an early-data block runs only after the check passed.
 */
class CorePeerClosesAtOnceTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun theConnectReturnsAndTheFirstOperationReportsThePeersClose() =
        coreTest("CorePeerClosesAtOnceTests.theConnectReturnsAndTheFirstOperationReportsThePeersClose") {
            mint().use { served ->
                withMintedCoreServer(served, onConnection = { it.close(QuicError.ApplicationError(APP_CODE)) }) { port ->
                    val pinned = coreTestOptions.copy(serverCertificateHashes = listOf(served.hash))
                    val connection = connectCore(port, loops = loops, options = pinned).connection
                    withTimeout(10.seconds) { connection.state.first { it is QuicConnectionState.Closed } }
                    val opened = assertFailsWith<QuicCloseException> { connection.openStream() }
                    assertEquals<QuicCloseReason>(QuicCloseReason.ByPeer(QuicError.ApplicationError(APP_CODE)), opened.closeReason)
                    val accepted = assertFailsWith<QuicCloseException> { connection.acceptStream() }
                    assertEquals(opened.closeReason, accepted.closeReason)
                    assertEquals("test", connection.negotiatedAlpn)
                    assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), connection.resumption)
                }
            }
        }

    @Test
    fun aServerTheVerifierRefusesStillFailsTheConnectWhenItClosedAtOnce() =
        coreTest("CorePeerClosesAtOnceTests.aServerTheVerifierRefusesStillFailsTheConnectWhenItClosedAtOnce") {
            mint().use { served ->
                val refusing = ServerCertVerifier { ServerCertVerdict.Rejected("not ours") }
                withMintedCoreServer(served, onConnection = { it.close(QuicError.ApplicationError(APP_CODE)) }) { port ->
                    assertFailsWith<ServerCertificateRejectedException> {
                        connectCore(port, loops = loops, options = coreTestOptions.copy(serverCertVerifiers = listOf(refusing)))
                    }
                }
            }
        }

    @Test
    fun aServerPinnedToAnotherCertificateStillFailsTheConnectWhenItClosedAtOnce() =
        coreTest("CorePeerClosesAtOnceTests.aServerPinnedToAnotherCertificateStillFailsTheConnectWhenItClosedAtOnce") {
            mint().use { pinned ->
                mint().use { served ->
                    withMintedCoreServer(served, onConnection = { it.close(QuicError.ApplicationError(APP_CODE)) }) { port ->
                        assertFailsWith<CertificateHashPinningException> {
                            connectCore(port, loops = loops, options = coreTestOptions.copy(serverCertificateHashes = listOf(pinned.hash)))
                        }
                    }
                }
            }
        }

    @Test
    fun aVerifierStillCheckingWhenTheServersCloseArrivesDecidesTheConnect() =
        coreTest("CorePeerClosesAtOnceTests.aVerifierStillCheckingWhenTheServersCloseArrivesDecidesTheConnect") {
            mint().use { served ->
                for (verdict in listOf<ServerCertVerdict>(ServerCertVerdict.Trusted, ServerCertVerdict.Rejected("not ours"))) {
                    val closed = CompletableDeferred<Unit>()
                    // The verifier answers only once the server closed and its CONNECTION_CLOSE has had time to land.
                    val waiting =
                        ServerCertVerifier {
                            closed.await()
                            delay(CLOSE_LANDS)
                            verdict
                        }
                    val options = coreTestOptions.copy(serverCertVerifiers = listOf(waiting))
                    withMintedCoreServer(served, onConnection = {
                        it.close(QuicError.ApplicationError(APP_CODE))
                        closed.complete(Unit)
                    }) { port ->
                        when (verdict) {
                            is ServerCertVerdict.Trusted -> {
                                val connection = connectCore(port, loops = loops, options = options).connection
                                assertIs<EdgeEnd.Ended>(connection.end(), "the close landed while the server was being checked")
                                val opened = assertFailsWith<QuicCloseException> { connection.openStream() }
                                assertEquals<QuicCloseReason>(
                                    QuicCloseReason.ByPeer(QuicError.ApplicationError(APP_CODE)),
                                    opened.closeReason,
                                )
                            }
                            is ServerCertVerdict.Rejected ->
                                assertFailsWith<ServerCertificateRejectedException> { connectCore(port, loops = loops, options = options) }
                        }
                    }
                }
            }
        }

    @Test
    fun anEarlyDataBlockRunsOnlyAfterTheServerPassedItsCheck() =
        coreTest("CorePeerClosesAtOnceTests.anEarlyDataBlockRunsOnlyAfterTheServerPassedItsCheck") {
            mint().use { served ->
                var ran = false
                val streams = AtomicInt(0)
                val refusing = ServerCertVerifier { ServerCertVerdict.Rejected("not ours") }
                // Bytes that are not a ticket: quiche refuses them, so the block runs after the handshake, not as 0-RTT.
                val unusable = QuicSessionTicket.restore(BufferFactory.network().allocate(8).also { it.resetForRead() }, "test")
                val options =
                    coreTestOptions.copy(
                        serverCertVerifiers = listOf(refusing),
                        resumption =
                            QuicResumption.ResumeWithEarlyData(unusable) {
                                ran = true
                                openStream().writeFully(BufferFactory.network().allocate(4).also { it.resetForRead() }, 10.seconds)
                            },
                    )
                withMintedCoreServer(served, onConnection = {
                    it.acceptStream()
                    streams.incrementAndFetch()
                }) { port ->
                    assertFailsWith<ServerCertificateRejectedException> { connectCore(port, loops = loops, options = options) }
                    delay(CLOSE_LANDS)
                    assertFalse(ran, "the early-data block never ran against a server that failed its check")
                    assertEquals(0, streams.load(), "the server never saw a stream")
                }
            }
        }

    @Test
    fun aServerThePlatformStoreRefusesAfterTheHandshakeFailsTheConnectWhenItClosedAtOnce() =
        coreTest("CorePeerClosesAtOnceTests.aServerThePlatformStoreRefusesAfterTheHandshakeFailsTheConnectWhenItClosedAtOnce") {
            val storeChecks = coreTestOptions.copy(verifyPeer = true)
            when (platformChainTrust(storeChecks)) {
                // Only where the platform's store checks after the handshake (Apple's SecTrust on iOS, tvOS, watchOS).
                is PlatformChainTrust.InHandshake -> return@coreTest
                is PlatformChainTrust.AfterHandshake -> Unit
            }
            mint().use { served ->
                withMintedCoreServer(served, onConnection = { it.close(QuicError.ApplicationError(APP_CODE)) }) { port ->
                    val refused =
                        assertFailsWith<ServerCertificateRejectedException> { connectCore(port, loops = loops, options = storeChecks) }
                    assertIs<ServerCertificateRejection.UntrustedBySystem>(refused.rejection, "a minted certificate no store trusts")
                }
            }
        }

    private companion object {
        const val APP_CODE = 0x2AL
        val CLOSE_LANDS = 300.milliseconds
    }
}
