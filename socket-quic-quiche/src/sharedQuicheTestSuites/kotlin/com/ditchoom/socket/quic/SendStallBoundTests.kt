package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **The send stall backstop must exist on every clock, and must say so in the trace.**
 *
 * `QuicheDriver.flushOutgoing` bounds one `UdpChannel.send` so a platform that stops answering cannot
 * park the driver loop — the defect behind a 48.6-hour field hang, pinned by
 * [IdleTimeoutTerminationTests.theIdleTimerTerminatesAConnectionWhoseSendsNeverReturn]. These two
 * cover the parts of that bound which are easy to get wrong *after* it works:
 *
 * 1. that it survives being routed through [DriverClock] — a test clock that quietly dropped it would
 *    let the very defect it prevents reappear inside the suites meant to catch defects; and
 * 2. that a stall is *recorded*. The field hang went undiagnosed for two days precisely because the
 *    recording said nothing about it, so a new failure mode that appears in no trace would be the
 *    same mistake with a shorter fuse.
 */
class SendStallBoundTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Collects what the driver recorded, so a test asserts on typed events rather than on log text. */
    private class CollectingSink : TraceSink {
        val events = mutableListOf<TraceEvent>()

        override fun emit(event: TraceEvent) {
            events += event
        }

        fun stalls(): List<TraceEvent.Error> = events.filterIsInstance<TraceEvent.Error>().filter { it.type.contains("SendStalled") }
    }

    /**
     * Tier-1: the backstop is reachable under [ManualDriverClock], fired by hand like every other timer.
     *
     * [ManualDriverClock] deliberately ignores the durations it is given — the test decides when time
     * passes — and the obvious way to write its `withBound` is to run the block unbounded and note in a
     * comment that the backstop "does not apply here". That note would be the bug: Tier-1 runs on the
     * real `Dispatchers.Default`, so a wedged send under it would hang the lane on exactly the defect
     * the bound exists to prevent, and no amount of prose in the clock would stop it.
     *
     * So the bound is hand-fired instead of absent, and this test is what holds that line: it wedges a
     * send, fires the bound with [ManualDriverClock.stall], and requires the driver to have closed the
     * path. Delete `stall()` and this goes red rather than silently reintroducing the hang.
     */
    @Test
    fun theStallBackstopIsFirableUnderTheManualClock() =
        runQuicTest(timeout = 10.seconds) {
            val api = StubQuicheApi()
            api.established = true
            // A timer to fire, so the flush that parks the send has something to drive it.
            api.connTimeout = 1.seconds
            api.closeOnTimeout = false

            val wedged = WedgedUdpChannel()
            val clock = ManualDriverClock()
            // Tier-1 runs on the REAL dispatcher, so the driver loop must too. Passing
            // `EmptyCoroutineContext` here — the right choice for the virtual-time suites — puts the
            // loop on `runTest`'s test dispatcher while this body runs on `Dispatchers.Default`, and
            // nothing then pumps the scheduler: the loop never even reaches `armTimeout`, and the
            // manual clock waits forever for an arm token that cannot come.
            val driver =
                createTestDriver(api, udpChannel = wedged, clock = clock, driverContext = Dispatchers.Default)
            driver.start(this)
            try {
                // Armed AFTER start on purpose: set before, the *startup* flush consumes it and the
                // send parks before the loop ever reaches `armTimeout`, leaving no initial arm token
                // for the manual clock to consume — the fire then waits forever on a token that was
                // never emitted.
                api.connSendOnce = 1200
                // Every step is separately bounded so a hang names its own stage instead of surfacing
                // as the runner's cap, which says only that something somewhere stopped.
                // Fired from its OWN coroutine, and this is not a style choice. The tick channel is a
                // RENDEZVOUS, and its `send` resumes only once the driver's timer branch reaches its
                // next suspension — which, when that branch parks in a wedged send, it never does. So
                // `fireExpectingNoRearm` awaited inline deadlocks against the very condition this test
                // sets up. Measured, not guessed: firing inline hangs, firing detached does not.
                val firing = async { clock.fireExpectingNoRearm(1.seconds) }
                assertNotNull(
                    withTimeoutOrNull(3.seconds) { while (wedged.sendCount == 0) yield() },
                    "stage 1: the timer fire never reached a send (sendCount stayed 0)",
                )
                assertEquals(
                    0,
                    wedged.closeCount,
                    "the path was closed before the bound was ever fired — the manual clock is not " +
                        "waiting, so this test could not tell a working backstop from an eager one",
                )

                assertNotNull(
                    withTimeoutOrNull(3.seconds) { clock.stall() },
                    "stage 2: firing the send bound never rendezvoused — the driver is not parked in withBound",
                )
                assertNotNull(
                    withTimeoutOrNull(3.seconds) { while (wedged.closeCount == 0) yield() },
                    "stage 3: the bound fired but the driver never closed the wedged path",
                )
                firing.await()
                assertEquals(
                    1,
                    wedged.closeCount,
                    "firing the send bound by hand did not make the driver close the wedged path. The " +
                        "backstop is missing under this clock, so any Tier-1 test that wedges a send " +
                        "hangs the lane instead of failing it.",
                )
            } finally {
                wedged.release()
                driver.commands.close()
            }
        }

    /**
     * A stalled send reaches the trace.
     *
     * The recording `UdpChannel` decorator cannot see this one: the bound in `flushOutgoing` wraps the
     * call *to* the decorator, so a stalled send never returns through it. The driver therefore has to
     * record the stall itself, and if it stops doing so the only evidence of a wedged datapath is a
     * gap in the trace where the datagrams used to be — which is the exact artifact that made the
     * field hang take a 72-hour walk and two days of silence to explain.
     */
    @Test
    fun aStalledSendIsRecordedInTheTrace() =
        runTest {
            val sink = CollectingSink()
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            api.closeOnTimeout = false

            val bound = 2.seconds
            val wedged = WedgedUdpChannel()
            val driver =
                createTestDriver(
                    api,
                    udpChannel = wedged,
                    sendStallBound = bound,
                    capture = TraceCapture.On(QuicTraceRecorder(sink)),
                )
            // Its own Job, same (virtual-time) dispatcher: a recorder makes `start` launch two
            // state-collector coroutines that live as long as the scope, and as children of the test
            // job they would keep `runTest` waiting until its own budget expired.
            val driverScope = CoroutineScope(coroutineContext + Job())
            driver.start(driverScope)
            try {
                runCurrent()
                api.connSendOnce = 1200
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()
                assertTrue(
                    wedged.sendCount > 0,
                    "no send was attempted, so nothing could stall and this test proved nothing",
                )
                assertEquals(
                    0,
                    sink.stalls().size,
                    "a stall was recorded before the bound elapsed",
                )

                testScheduler.advanceTimeBy(bound + 1.milliseconds)
                runCurrent()

                assertEquals(
                    1,
                    sink.stalls().size,
                    "a send that stalled produced no trace event. The datagrams simply stop, which is " +
                        "indistinguishable in the artifact from a quiet connection — the recording has " +
                        "to name the stall or the next occurrence costs another field walk. " +
                        "Recorded: ${sink.events.map { it::class.simpleName }}",
                )
            } finally {
                wedged.release()
                driver.commands.close()
                driverScope.cancel()
            }
        }

    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        udpChannel: UdpChannel = StubUdpChannel(),
        clock: DriverClock = RealDriverClock,
        driverContext: CoroutineContext = EmptyCoroutineContext,
        sendStallBound: Duration = DEFAULT_SEND_STALL_BOUND,
        capture: TraceCapture = TraceCapture.Off,
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
            capture = capture,
        )
}
