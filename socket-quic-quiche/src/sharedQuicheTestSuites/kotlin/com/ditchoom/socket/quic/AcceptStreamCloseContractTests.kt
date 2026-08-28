package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The end of a connection has to reach a parked acceptor as *the connection's close*, not as a
 * coroutines internal (#488).
 *
 * `QuicheDriver.cleanup()` ends teardown with `incomingStreams.close()`. Anything parked in
 * [QuicConnection.acceptStream] at that moment is resumed by that close, and while `acceptStream`
 * was a bare `receive()` it was resumed with `ClosedReceiveChannelException` — a control-flow signal
 * wearing an error type, from a library whose own rule is that close reasons stay typed. A caller
 * could not tell "the peer closed cleanly and there will be no more streams" from "the driver died",
 * because both arrived as the same channel exception carrying no reason at all.
 *
 * It cost two unrelated PRs a CI cycle in a day, on two different backends (Android/JNI, JVM/FFM),
 * both from the same shape: a server handler parked in `acceptStream()` for a whole test body and
 * resumed by teardown.
 *
 * ## Why this reproduces without the race
 *
 * The flake needed `cleanup()` to win a race against the accept loop's cancellation. The *contract*
 * does not: it is simply what a parked acceptor observes when the channel it waits on is closed. So
 * this closes `incomingStreams` directly — the last thing `cleanup()` does — under a deliberately
 * parked acceptor, and asserts what comes back. Deterministic, no network, and it fails on the
 * defect rather than on the timing that happened to expose it.
 */
class AcceptStreamCloseContractTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun aParkedAcceptorLearnsTheConnectionEndedRatherThanThatAChannelClosed() =
        runQuicTest {
            val driver = closeContractDriver()
            val connection = DriverQuicConnection(driver, bufferFactory, PEER, this)
            val thrown = CompletableDeferred<Throwable>()

            launch {
                try {
                    connection.acceptStream()
                    thrown.complete(NoTerminal)
                } catch (t: Throwable) {
                    thrown.complete(t)
                }
            }
            // Park it before the close, or the test proves nothing about a suspended receiver.
            yield()

            driver.incomingStreams.close() // the last thing QuicheDriver.cleanup() does

            val terminal = withTimeout(TIMEOUT) { thrown.await() }
            assertIs<QuicCloseException>(
                terminal,
                "a parked acceptStream() must report the connection's close, but it reported " +
                    "${terminal::class.simpleName}: ${terminal.message}",
            )
        }

    @Test
    fun theTerminalCarriesTheDriversOwnCloseReasonRatherThanAFreshlyInventedOne() =
        runQuicTest {
            // A typed exception that made its reason up would satisfy the test above while still
            // telling the caller nothing, so the reason must be the one the driver itself records.
            val driver = closeContractDriver()
            val connection = DriverQuicConnection(driver, bufferFactory, PEER, this)
            val thrown = CompletableDeferred<Throwable>()

            launch {
                try {
                    connection.acceptStream()
                    thrown.complete(NoTerminal)
                } catch (t: Throwable) {
                    thrown.complete(t)
                }
            }
            yield()
            driver.incomingStreams.close()

            val terminal = assertIs<QuicCloseException>(withTimeout(TIMEOUT) { thrown.await() })
            assertEquals(
                driver.closeReasonOr(QuicError.NoError),
                terminal.closeReason,
                "the terminal must carry the driver's recorded close reason",
            )
        }

    private fun closeContractDriver(): QuicheDriver =
        QuicheDriver(
            // Never exercises a path move: nothing here runs the driver loop at all.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = StubQuicheApi(),
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = true,
            keepAliveInterval = null,
            clock = RealDriverClock,
        )

    private companion object {
        private val PEER = SocketAddress.ofLiteral("127.0.0.1", 4433)
        private val TIMEOUT = 2.seconds

        /** Completing with this rather than leaving the deferred unset turns a hang into a diagnosis. */
        private val NoTerminal = IllegalStateException("acceptStream() returned a stream instead of ending")
    }
}
