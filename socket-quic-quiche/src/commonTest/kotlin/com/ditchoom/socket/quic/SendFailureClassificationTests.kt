package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins how a **failed UDP send** is classified by [QuicheDriver.flushOutgoing].
 *
 * Today it is classified as fatal, unconditionally:
 *
 * ```kotlin
 * } catch (_: Exception) {
 *     // UDP send failed (peer unreachable, channel closed during shutdown, etc).
 *     // The connection cannot make further progress — short-circuit to Closed
 *     transitionToClosed()
 * ```
 *
 * That comment is an assumption, and these tests exist to check it rather than trust it. It is
 * false in two ways that matter:
 *
 * 1. **Transient errors are not fatal.** `ENOBUFS`, `EAGAIN`, and a momentary `EHOSTUNREACH` during
 *    an interface flap are all recoverable — the datagram is lost, which QUIC already handles by
 *    retransmitting. Tearing the connection down converts a lost packet into a lost session, and
 *    surviving exactly this is the reason to run QUIC.
 *
 * 2. **A path is not the connection.** Once a migration is in flight there are two live paths, and
 *    quiche keeps sending on the old one until the new one validates. A real handoff only happens
 *    *because* the old path died, so the first send after the switch begins throws and closes the
 *    connection before migration can finish. That makes active migration unable to do the one job
 *    it exists for.
 *
 * ## Why this is not an Apple-only test
 * The measured Apple symptom is the sharpest (a UDP `nw_connection_t` fails ~2s after its path
 * disappears and never recovers), but this code is in `commonMain` and Linux/JVM reach the same
 * branch via `ENETUNREACH`. It stayed invisible because every existing migration test — JVM,
 * Linux, and the new shared suite — migrates away from a **healthy** path: `127.0.0.1` never dies.
 * Nothing covers the only sequence that happens in production.
 *
 * These run on the [StubQuicheApi]/[StubUdpChannel] driver harness, so they are hermetic and
 * deterministic: no sockets, no network, no timing luck.
 */
class SendFailureClassificationTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** A recoverable send error, of the kind a real datapath raises transiently (ENOBUFS/EAGAIN). */
    private class TransientSendFailure : RuntimeException("ENOBUFS (transient)")

    @Test
    fun aTransientSendFailureDoesNotCloseTheConnection() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true

            var failNextSend = true
            val channel =
                StubUdpChannel { _, _ ->
                    if (failNextSend) {
                        failNextSend = false
                        throw TransientSendFailure()
                    }
                }

            val driver = createTestDriver(api, udpChannel = channel)
            driver.start(this)
            try {
                // Force exactly one flushOutgoing iteration, which hits the failing send.
                api.connSendOnce = 1200
                sendOpenStream(driver)

                // Today this finds Closed almost immediately (the bug). Once a send failure is
                // classified rather than assumed fatal, no Closed state ever arrives and this
                // times out to null — so the assertion states the requirement, not the symptom.
                val closed =
                    withTimeoutOrNull(2.seconds) {
                        driver.state.first { it is QuicConnectionState.Closed }
                    }

                assertNull(
                    closed,
                    "a single transient UDP send failure tore down the whole QUIC connection " +
                        "($closed). A lost datagram is what QUIC retransmits; it must not become a " +
                        "lost session. flushOutgoing classifies every send exception as fatal.",
                )
            } finally {
                driver.destroy()
            }
        }

    /**
     * The direct refutation of the comment's claim — "the connection cannot make further progress".
     * It can: the very next send succeeds. Separated from the test above so a failure names which
     * half of the assumption broke.
     */
    @Test
    fun theConnectionStillMakesProgressAfterATransientSendFailure() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true

            var failNextSend = true
            val channel =
                StubUdpChannel { _, _ ->
                    if (failNextSend) {
                        failNextSend = false
                        throw TransientSendFailure()
                    }
                }

            val driver = createTestDriver(api, udpChannel = channel)
            driver.start(this)
            try {
                api.connSendOnce = 1200
                sendOpenStream(driver)

                // Give the failing flush a chance to land before asking for a second one.
                withTimeoutOrNull(1.seconds) {
                    driver.state.first { it is QuicConnectionState.Closed }
                }

                // A second flush: with the failure consumed, this send succeeds. The driver must
                // still be alive to perform it.
                //
                // Wrapped in runCatching deliberately. Once transitionToClosed() has run, the
                // command channel is closed and this throws ClosedSendChannelException *before*
                // reaching any assertion — so an unwrapped call goes red with an incidental
                // exception that names none of the above. Capturing it lets the assertion below
                // state the actual defect either way.
                val before = channel.sendCount
                val secondFlush =
                    runCatching {
                        api.connSendOnce = 1200
                        sendOpenStream(driver)
                    }

                assertTrue(
                    secondFlush.isSuccess && channel.sendCount > before,
                    "the driver could not send again after one transient failure — the connection " +
                        "was torn down rather than retransmitting, making 'cannot make further " +
                        "progress' self-fulfilling. sendCount $before -> ${channel.sendCount}; " +
                        "second flush: ${secondFlush.exceptionOrNull() ?: "accepted"}",
                )
            } finally {
                driver.destroy()
            }
        }

    private suspend fun sendOpenStream(driver: QuicheDriver): StreamSlot {
        val deferred = CompletableDeferred<StreamSlot>()
        driver.commands.send(QuicheCmd.OpenStream(deferred))
        return withTimeout(2.seconds) { deferred.await() }
    }

    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        udpChannel: UdpChannel = StubUdpChannel(),
    ): QuicheDriver =
        QuicheDriver(
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udpChannel,
            clientMode = false,
            isServer = false,
        )
}
