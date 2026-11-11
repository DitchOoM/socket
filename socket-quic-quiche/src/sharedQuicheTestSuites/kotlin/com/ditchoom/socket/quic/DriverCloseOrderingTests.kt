package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Everything an outside caller reads *because* the command channel is closed must already be settled
 * when the channel closes.
 *
 * A caller learns the connection is gone by hitting the closed channel, and then asks the driver two
 * things: why ([QuicheDriver.closeReasonOr]) and what the peer's certificate was
 * ([QuicheDriver.copyRetainedPeerCert]). The driver closes the channel on its own loop, and the
 * published `Closed` state follows later in the same turn; the observer here sits in that gap, on the
 * driver's own thread, so the interleaving is exact rather than raced for.
 */
class DriverCloseOrderingTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** What a caller sees when it looks at the driver at one instant inside the close. */
    private data class Observation(
        val channelClosed: Boolean,
        val reason: QuicCloseReason,
        val certificateLength: Int,
    )

    /**
     * A [StubQuicheApi] that lets the test look at the driver from inside the close: [connPeerError] is
     * read by `transitionToClosed` while it resolves the reason, which sits between closing the channel
     * and publishing `Closed` on an unfixed driver.
     */
    private class ObservedApi(
        private val stub: StubQuicheApi,
    ) : QuicheApi by stub {
        @Volatile
        lateinit var driver: QuicheDriver

        val observations = mutableListOf<Observation>()

        override fun connPeerError(conn: QuicheConn): QuicError? {
            val der = BufferFactory.deterministic().allocate(CERT_CAPACITY)
            try {
                observations +=
                    Observation(
                        channelClosed = driver.commands.isClosedForSend,
                        reason = driver.closeReasonOr(QuicError.NoError),
                        certificateLength = driver.copyRetainedPeerCert(PeerCertPosition.Leaf, der, CERT_CAPACITY),
                    )
            } finally {
                der.freeIfNeeded()
            }
            return stub.connPeerError(conn)
        }
    }

    @Test
    fun aCallerThatFindsTheChannelClosedAlreadyHasTheReason() =
        runTest(timeout = 30.seconds) {
            val closed =
                closeAndObserve { api, seen ->
                    seen.forEach {
                        assertEquals(
                            QuicCloseReason.ByPeer(QuicError.ProtocolViolation),
                            it.reason,
                            "a caller that found the command channel closed was told the wrong reason",
                        )
                    }
                    assertTrue(api.observations.isNotEmpty(), "the close never resolved a reason, so nothing was observed")
                }
            assertEquals(QuicCloseReason.ByPeer(QuicError.ProtocolViolation), closed.reason)
        }

    @Test
    fun aCallerThatFindsTheChannelClosedStillSeesThePeersCertificate() =
        runTest(timeout = 30.seconds) {
            closeAndObserve { api, seen ->
                seen.forEach {
                    assertEquals(CERT_LEN, it.certificateLength, "a caller that found the channel closed saw no peer certificate")
                }
                assertTrue(api.observations.isNotEmpty(), "the close never resolved a reason, so nothing was observed")
            }
        }

    /**
     * Drives a client driver to a peer-closed state with a certificate chain on the connection, then hands
     * [check] what was observed from inside the close. After the fix the observer runs before the channel
     * closes, so there may be nothing in the window; what must hold is that nobody who sees a closed
     * channel sees a wrong answer.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.closeAndObserve(
        check: (ObservedApi, List<Observation>) -> Unit,
    ): QuicConnectionState.Closed {
        val stub = StubQuicheApi()
        stub.peerError = QuicError.ProtocolViolation
        stub.peerCertChainLens = listOf(CERT_LEN)
        val api = ObservedApi(stub)
        val driver = createTestDriver(api)
        api.driver = driver
        driver.start(this)
        try {
            runCurrent()
            stub.closed = true
            driver.commands.trySend(QuicheCmd.Stats(kotlinx.coroutines.CompletableDeferred()))
            runCurrent()
            val closed = assertIs<QuicConnectionState.Closed>(driver.state.value, "the connection never closed")
            check(api, api.observations.filter { it.channelClosed })
            return closed
        } finally {
            driver.destroy()
        }
    }

    private fun createTestDriver(api: QuicheApi): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
            clock = RealDriverClock,
            driverContext = EmptyCoroutineContext,
        )

    private companion object {
        const val CERT_LEN = 700
        const val CERT_CAPACITY = 4096
    }
}
