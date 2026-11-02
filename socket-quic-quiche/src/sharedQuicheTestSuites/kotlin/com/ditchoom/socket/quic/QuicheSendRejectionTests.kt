package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the driver does with each non-datagram `quiche_conn_send` return ([QuicheSendStop]).
 *
 * The defect: `flushOutgoing` read every `written <= 0` as "nothing to send". Two quiche bugs (patched in
 * #764/#765) left quiche answering *every* send with `-6` (`QUICHE_ERR_INVALID_STATE`); the driver
 * swallowed it, the connection sent nothing — not even ACKs — and died 30 s later as an unexplained
 * idle timeout.
 *
 * Every test runs on a [ManualDriverClock] that is never advanced, so no quiche timer — the idle timer
 * included — can fire: a close observed here happened at zero virtual time, from the send result alone.
 *
 * A UDP send failure is a different event with the opposite verdict (it is NOT terminal — see
 * ReactiveDriverTests.flushOutgoing_keepsConnectionAliveOnUdpError); these tests are about quiche's
 * return code only.
 */
class QuicheSendRejectionTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun decode_classifiesEveryNonPositiveReturn() {
        assertEquals(QuicheSendStop.NothingToSend, QuicheSendStop.decode(QuicheDriver.QUICHE_ERR_DONE))
        assertEquals(QuicheSendStop.NothingToSend, QuicheSendStop.decode(0))
        assertEquals(QuicheSendStop.BufferTooShort, QuicheSendStop.decode(-2))
        assertEquals(QuicheSendStop.Rejected(-6), QuicheSendStop.decode(-6))
        assertEquals(QuicheSendStop.Rejected(-10), QuicheSendStop.decode(-10))
        assertFailsWith<IllegalArgumentException> { QuicheSendStop.decode(1200) }
    }

    @Test
    fun sendRejectedForever_closesAtOnceWithTheTypedReason_notAtTheIdleTimeout() =
        runQuicTest {
            val api = StubQuicheApi()
            api.connTimeout = 30.seconds // the idle timer that used to be the only way out
            api.connSendFallback = -6 // QUICHE_ERR_INVALID_STATE, on every send, forever
            val udp = StubUdpChannel()
            val driver = createTestDriver(api, udp)
            driver.start(this)
            try {
                val closed = withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }

                val expected = QuicCloseReason.ByLocal(QuicError.SendRejected(-6))
                assertEquals(QuicConnectionState.Closed(expected), closed, "the close must name quiche's code")
                assertEquals(
                    listOf<QuicError>(QuicError.SendRejected(-6)),
                    api.closeErrors,
                    "quiche must be asked to close (INTERNAL_ERROR) before the driver gives up on it",
                )
                assertEquals(0x1L, api.closeErrors.single().code, "the wire code is INTERNAL_ERROR")
                assertEquals(0, api.onTimeoutCount, "no timer fired: this close came from the send result")
                assertEquals(0, udp.sendCount, "quiche produced nothing, so nothing was sent")
                assertTrue(driver.commands.isClosedForSend, "the command channel closes with the state")
                assertEquals(expected, driver.connectionClosed().closeReason, "the thrown channel agrees")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun sendRejectedOnce_closeFrameStillLeaves_andTheReasonKeepsQuichesCode() =
        runQuicTest {
            val api = StubQuicheApi()
            api.closesOnLocalClose = true // quiche reports closed once a local close is accepted
            api.connSendQueue += -6 // the rejection…
            api.connSendQueue += 1200 // …then the CONNECTION_CLOSE quiche emits after connClose
            api.connSendFallback = QuicheDriver.QUICHE_ERR_DONE
            // What real quiche reads back after accepting our INTERNAL_ERROR close: the code, not quiche's.
            api.localError = QuicError.fromTransportCode(0x1)
            val udp = StubUdpChannel()
            val driver = createTestDriver(api, udp)
            driver.start(this)
            try {
                val closed = withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }

                assertEquals(
                    QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.SendRejected(-6))),
                    closed,
                )
                assertEquals(1, udp.sendCount, "the follow-up flush must put the CONNECTION_CLOSE on the wire")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun sendRejectedWhileQuicheIsAlreadyClosing_keepsQuichesOwnReason() =
        runQuicTest {
            // A TLS failure records quiche's local error and returns TlsFail (-10) from send; quiche then
            // declines our close (Done). The reason quiche recorded is the connection's reason.
            val api = StubQuicheApi()
            api.connSendFallback = -10
            api.connCloseResult = QuicheDriver.QUICHE_ERR_DONE
            api.localError = QuicError.CryptoError(42)
            val driver = createTestDriver(api, StubUdpChannel())
            driver.start(this)
            try {
                val closed = withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }
                assertEquals(QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.CryptoError(42))), closed)
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun bufferTooShort_stopsThisFlushOnly_andTheNextFlushSends() =
        runQuicTest {
            val api = StubQuicheApi()
            api.connSendQueue += QuicheSendStop.QUICHE_ERR_BUFFER_TOO_SHORT
            api.connSendQueue += 1200
            val udp = StubUdpChannel()
            val driver = createTestDriver(api, udp)
            driver.start(this)
            try {
                // Established is published by the first flush's updateState, so the first flush is over.
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Established } }
                assertEquals(0, udp.sendCount, "BUFFER_TOO_SHORT ends the flush it happened in")
                assertEquals(listOf(1200), api.connSendQueue.toList(), "…without asking quiche again")

                runOneCommand(driver) // any command ends in a flush

                assertEquals(1, udp.sendCount, "the next flush must offer quiche a buffer again")
                assertIs<QuicConnectionState.Established>(driver.state.value, "BUFFER_TOO_SHORT is not terminal")
                assertEquals(emptyList(), api.closeErrors, "nothing may close the connection")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun done_isNotTerminal() =
        runQuicTest {
            val api = StubQuicheApi()
            api.connSendFallback = QuicheDriver.QUICHE_ERR_DONE // every send, forever
            val driver = createTestDriver(api, StubUdpChannel())
            driver.start(this)
            try {
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Established } }
                runOneCommand(driver)
                runOneCommand(driver)

                assertIs<QuicConnectionState.Established>(driver.state.value, "DONE is the normal end of a drain")
                assertEquals(emptyList(), api.closeErrors)
            } finally {
                driver.destroy()
            }
        }

    private suspend fun runOneCommand(driver: QuicheDriver) {
        val stats = CompletableDeferred<QuicStatsSnapshot>()
        driver.commands.send(QuicheCmd.Stats(stats))
        withTimeout(2.seconds) { stats.await() }
        // The flush runs in afterCommand(), after the result completes; a second round trip proves it ran.
        val fence = CompletableDeferred<QuicStatsSnapshot>()
        driver.commands.send(QuicheCmd.Stats(fence))
        withTimeout(2.seconds) { fence.await() }
    }

    private fun createTestDriver(
        api: StubQuicheApi,
        udp: UdpChannel,
    ): QuicheDriver =
        QuicheDriver(
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udp,
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
            keepAliveInterval = null,
            clock = ManualDriverClock(),
        )
}
