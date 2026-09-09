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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, so a `commonTest` home
 * covered every platform *except* the one that ships this backend to users: Android is the only target
 * that runs quiche over JNI, and it is where issue #393 was found in the field. This directory is
 * `srcDir`'d into both source sets, so the same source runs unchanged on jvm/apple/linux **and** on a
 * real device — the move adds the lane that was missing and takes none away. See DitchOoM/socket#390.
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

    /**
     * **The field test.** Every send *parks* — the connection must still be reaped by the idle timer.
     *
     * [theIdleTimerTerminatesAConnectionWhoseSendsAllFail] establishes that a connection whose sends
     * all *fail* is reaped. That is the case this suite was written for, and it is green. It leaves a
     * hole: it proves the idle timer arbitrates when the loop is still *running*. A send that never
     * returns takes the loop itself out, and with it every timer the loop is responsible for arming —
     * so the mechanism the suite exists to guarantee is not merely late, it is gone.
     *
     * The hole is not hypothetical. A 72-hour two-device walk (2026-09-03) recorded an iOS client
     * that echoed at full cadence — 338 echoes, 47 ms RTT, stream integrity intact — and then emitted
     * **nothing at all for 48.6 hours**: no read timeout, no send failure, no idle timeout, no
     * reconnect, while a heartbeat coroutine on a different dispatcher kept logging every 60 s. Seven
     * earlier connections in the same run died of `local: IdleTimeout` exactly as this suite says they
     * should, which is what rules out "the idle timer is broken in general" and points at the one
     * thing that was different about the last connection: its datapath stopped answering instead of
     * erroring. `flushOutgoing` awaits `channel.send(...)` inline on the driver loop with no bound
     * (`QuicheDriver.flushOutgoing`), so a parked send parks the loop.
     *
     * A connection that hangs forever is strictly worse than one that dies: a death reconnects, and
     * the walk's own log shows the reconnect path working nine times. This one stranded the client
     * for two days.
     */
    @Test
    fun theIdleTimerTerminatesAConnectionWhoseSendsNeverReturn() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            // Same staging as the sibling: phase 1 is a timer fire that only drives a flush, so the
            // wedged send is observed on its own before any close is attributable to it.
            api.closeOnTimeout = false

            val wedged = WedgedUdpChannel()

            val driver = createTestDriver(api, udpChannel = wedged)
            driver.start(this)
            try {
                runCurrent()

                // A timer fire is what reaches afterCommand() -> flushOutgoing(), i.e. how a
                // retransmit or keepalive actually attempts a send.
                api.connSendOnce = 1200
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()

                // Anti-vacuity: without an attempted send the driver was never parked and everything
                // below would pass on a connection that simply never tried to transmit.
                assertTrue(
                    wedged.sendCount > 0,
                    "no send was attempted, so the loop was never parked and this test proved nothing",
                )

                // Now give the protocol's own mechanism every chance: quiche's idle timeout expires,
                // and we advance far past it rather than by exactly one interval, so a merely-late
                // reap still counts as a pass.
                api.closeOnTimeout = true
                testScheduler.advanceTimeBy(60.seconds)
                runCurrent()

                val state = driver.state.value
                assertIs<QuicConnectionState.Closed>(
                    state,
                    "a connection whose sends never return was never reaped (state=$state) after 60s of " +
                        "a 1s idle timeout. The send parked the driver loop inline in flushOutgoing, so " +
                        "no further timer was ever armed and the connection cannot die — the 48.6-hour " +
                        "hang recorded on the 2026-09-03 walk. A dead path must terminate the connection " +
                        "so the caller can reconnect; hanging forever strands it instead.",
                )
                assertEquals(
                    QuicCloseReason.ByLocal(QuicError.IdleTimeout),
                    state.reason,
                    "a connection wedged by an unanswered datapath must report the idle timeout that " +
                        "reaped it, not some other cause",
                )
            } finally {
                // Unwind: nothing in production completes this, so the driver would otherwise hold the
                // test scope open and the real assertion above would be reported as a leaked coroutine.
                wedged.release()
                driver.commands.close()
            }
        }

    /**
     * The stall bound rides the **virtual** clock, so a simulation can reach it for free.
     *
     * Worth pinning because the bound is the one driver timer that does *not* go through
     * [DriverClock]: it is a plain `withTimeoutOrNull` in `flushOutgoing`, which resolves against
     * whatever dispatcher the loop runs on. Under the `driverContext = EmptyCoroutineContext` seam
     * that is the `kotlinx-coroutines-test` scheduler, so it shares one time source with everything
     * else the driver does — and this test is what says so, rather than the reader having to trace
     * `withTimeoutOrNull` to its dispatcher.
     *
     * The assertion discriminates: a wall-clock bound would not fire at all under `advanceTimeBy`,
     * and a bound that ignored its parameter would fire at the wrong offset. Advancing to one
     * millisecond *short* of the bound and then past it pins both ends. The whole test costs
     * microseconds of wall clock for a multi-second bound, which is the property being claimed.
     */
    @Test
    fun theSendStallBoundIsDrivenByTheVirtualClock() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            api.closeOnTimeout = false

            // Deliberately NOT the default, so this also proves the seam is threaded rather than the
            // driver quietly using a hardcoded value.
            val bound = 2.seconds
            val wedged = WedgedUdpChannel()
            val driver = createTestDriver(api, udpChannel = wedged, sendStallBound = bound)
            driver.start(this)
            try {
                runCurrent()

                // The timer fire drives the flush, and the send parks inside it at exactly this instant.
                api.connSendOnce = 1200
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()
                assertTrue(
                    wedged.sendCount > 0,
                    "no send was attempted, so nothing was ever parked and this test proved nothing",
                )
                assertEquals(
                    0,
                    wedged.closeCount,
                    "the stall bound fired the instant the send parked — it is not waiting at all",
                )

                testScheduler.advanceTimeBy(bound - 1.milliseconds)
                runCurrent()
                assertEquals(
                    0,
                    wedged.closeCount,
                    "the stall bound fired 1ms EARLY: it is not honouring the duration it was given",
                )

                testScheduler.advanceTimeBy(2.milliseconds)
                runCurrent()
                assertEquals(
                    1,
                    wedged.closeCount,
                    "the stall bound did not fire 1ms after its own duration of virtual time. Either it " +
                        "resolves against the wall clock — in which case no simulation can ever reach " +
                        "this branch, and the driver has two time sources instead of the one SimClock " +
                        "exists to guarantee — or it is not reading its parameter.",
                )
            } finally {
                wedged.release()
                driver.commands.close()
            }
        }

    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        udpChannel: UdpChannel = StubUdpChannel(),
        clock: DriverClock = RealDriverClock,
        driverContext: CoroutineContext = EmptyCoroutineContext,
        sendStallBound: Duration = DEFAULT_SEND_STALL_BOUND,
    ): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udpChannel,
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
            clock = clock,
            driverContext = driverContext,
            sendStallBound = sendStallBound,
        )
}
