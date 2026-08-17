package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Does the **idle timer** actually terminate a connection whose datapath is gone?
 *
 * This exists because of a decision, not a bug report. [SendFailureClassificationTests] shows that a
 * failed UDP send currently closes the whole connection, which makes active migration impossible —
 * a handoff happens *because* the old path died, so the first send after it kills the connection
 * before the new path can validate. The fix is to stop treating a send failure as terminal.
 *
 * But that only holds up if something *else* still terminates a connection whose only path is
 * permanently dead. RFC 9000 §10 enumerates exactly three ways a QUIC connection ends — idle
 * timeout, immediate close (CONNECTION_CLOSE), and stateless reset. "A local send failed" is not
 * among them, so the protocol's own answer is the idle timer. These tests check that our driver
 * actually implements that answer, instead of taking the RFC's word for it.
 *
 * If [theIdleTimerTerminatesAConnectionWhoseSendsAllFail] cannot be made to pass, then dropping the
 * close-on-send-failure behaviour would strand dead connections forever, and the fix needs a bounded
 * failure budget instead. That is the whole point of measuring before changing.
 *
 * Runs on the virtual-time scheduler ([runTest] + `driverContext = EmptyCoroutineContext` +
 * [RealDriverClock], the [VirtualTimeDriverTests] seam), so a 30-second idle timeout costs no
 * wall-clock time and the result is deterministic rather than timing-dependent.
 */
class IdleTimeoutTerminationTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** A send error of the kind a dead interface raises on every attempt (EHOSTUNREACH/ENETUNREACH). */
    private class DeadPathSendFailure : RuntimeException("ENETUNREACH (path gone)")

    /**
     * Baseline: the idle timer works at all, on a **healthy** channel.
     *
     * Expected green today — it pins the mechanism the fix will lean on, so that if the test below
     * ever fails we can tell "the idle timer is broken" apart from "send failures suppress it".
     */
    @Test
    fun theIdleTimerTerminatesAnInactiveConnectionWithATypedReason() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            // quiche's own idle timer: a timer fire closes the connection, and the stub raises
            // `timedOut` itself as part of that fire — no need to pre-declare a timeout that has not
            // happened yet.
            api.connTimeout = 5.seconds
            api.closeOnTimeout = true

            val driver = createTestDriver(api)
            driver.start(this)
            try {
                runCurrent()
                testScheduler.advanceTimeBy(5.seconds)
                runCurrent()

                // Anti-vacuity: if no timer ever reached quiche, everything below would pass by
                // accident on a connection that simply never did anything.
                assertTrue(api.onTimeoutCount > 0, "no timer fire reached quiche — this test proved nothing")

                val state = driver.state.value
                assertIs<QuicConnectionState.Closed>(
                    state,
                    "the idle timer did not terminate an inactive connection — the mechanism RFC 9000 §10 " +
                        "designates for this is not wired, so nothing would reap a dead connection once " +
                        "send failures stop being fatal",
                )
                assertEquals(
                    QuicCloseReason.ByLocal(QuicError.IdleTimeout),
                    state.reason,
                    "an idle-timed-out connection must say so. Reporting anything else — and especially " +
                        "Graceful, which is what the old nullable produced — leaves callers unable to " +
                        "tell a network failure from a peer that closed politely.",
                )
            } finally {
                driver.commands.close()
            }
        }

    /**
     * **The decision test.** Every send fails, forever — a permanently dead path. The connection must
     * still terminate, and terminate through the idle timer with a truthful reason.
     *
     * This was the decision point for the whole change: it went red because the first failed send
     * short-circuited to a terminal close long before any timer fired — reported, under the nullable
     * that [QuicCloseReason] replaced, as `Closed(error=null)`, the *clean shutdown* value, for what
     * was a network failure. Green needed both halves: send failures stopped being terminal, and the
     * idle timer was left to arbitrate. It now also pins the reason, so a close cannot claim to be an
     * idle timeout unless the idle timer actually produced it.
     */
    @Test
    fun theIdleTimerTerminatesAConnectionWhoseSendsAllFail() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            // Phase 1 must NOT close: we need a timer fire that only drives a flush, so the send
            // failure is observed on its own. `timedOut` stays false because no timer has closed
            // anything yet — which is what makes the close-reason assertion below discriminating. An
            // immediate send-failure close would surface as Unspecified (nothing was exchanged and
            // nothing timed out), while a genuine idle close surfaces as ByLocal(IdleTimeout).
            api.closeOnTimeout = false

            // A path that is gone: every send throws, not just the first.
            val deadChannel = StubUdpChannel { _, _ -> throw DeadPathSendFailure() }

            val driver = createTestDriver(api, udpChannel = deadChannel)
            driver.start(this)
            try {
                runCurrent()

                // A timer fire is what reaches afterCommand() -> flushOutgoing(), so this is how a
                // retransmit or keepalive actually attempts a send. Setting connSendOnce alone does
                // nothing: the driver is parked, and the earlier version of this test passed
                // vacuously because no send was ever attempted.
                api.connSendOnce = 1200
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()

                assertTrue(
                    deadChannel.sendCount > 0,
                    "no send was attempted, so the dead path was never exercised and this test proved " +
                        "nothing about how a send failure is classified",
                )

                val afterFailure = driver.state.value
                assertIs<QuicConnectionState.Established>(
                    afterFailure,
                    "a failed send on a dead path terminated the connection immediately ($afterFailure). " +
                        "That is the behaviour that makes migration impossible: a handoff happens because " +
                        "the old path died, so this fires before the new path can be validated.",
                )

                // Now let the protocol's own mechanism do its job: the next timer fire is quiche's
                // idle timeout expiring.
                api.closeOnTimeout = true
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()

                val state = driver.state.value
                assertIs<QuicConnectionState.Closed>(
                    state,
                    "a connection whose every send fails was never reaped — with close-on-send-failure " +
                        "removed, the idle timer must still terminate it or it would leak forever",
                )
                assertEquals(
                    QuicCloseReason.ByLocal(QuicError.IdleTimeout),
                    state.reason,
                    "the connection died of a dead network path but did not report an idle timeout. " +
                        "Under the old nullable this surfaced as Closed(error=null) — a claim of clean " +
                        "shutdown — which is what made this failure mode undiagnosable from the outside.",
                )
            } finally {
                driver.commands.close()
            }
        }

    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        udpChannel: UdpChannel = StubUdpChannel(),
        clock: DriverClock = RealDriverClock,
        driverContext: CoroutineContext = EmptyCoroutineContext,
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
            clock = clock,
            driverContext = driverContext,
        )
}
