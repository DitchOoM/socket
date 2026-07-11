package com.ditchoom.socket.quic.sim

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.DriverClock
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.RealDriverClock
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.StubUdpChannel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Proof of the W2 SimClock finding (see [SimClock]'s KDoc):
 *
 * 1. Under `runTest` + the W1 `driverContext` seam, a plain `onTimeout` arm is fully deterministic —
 *    `ManualDriverClock`'s rendezvous/re-arm protocol (built to tame REAL-dispatcher races) is
 *    unnecessary on the single-threaded virtual scheduler.
 * 2. The load-bearing half of the bridge is [SimClock.markNow] reading the scheduler's virtual time:
 *    with `RealDriverClock`'s monotonic mark, virtual-time advances leave the keepalive deadline
 *    frozen at its full interval across non-keepalive wakes, and the PING can silently never fire.
 */
class SimClockTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun simClock_keepalivePing_firesAtExactVirtualInstants() =
        runTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = SimClock(testScheduler))
            driver.start(this)
            try {
                runCurrent()
                assertEquals(0, api.ackElicitingCount, "no PING before the keepalive interval elapses")
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(1, api.ackElicitingCount, "keepalive PING must fire at exactly virtual t+1s")
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(2, api.ackElicitingCount, "keepalive PING must fire again at exactly virtual t+2s")
                assertEquals(0, api.onTimeoutCount, "keepalive deadlines must not be handed to quiche")
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun simClock_virtualMarkNow_keepsKeepaliveDeadlineAcrossQuicheTimerWakes() =
        runTest {
            // Quiche timer (7s) shorter than the keepalive interval (10s): the t=7s wake goes to
            // quiche WITHOUT resetting activity, so the keepalive deadline must keep shrinking and
            // fire at exactly t=10s. Only a markNow reading VIRTUAL time can see the 7s as elapsed.
            val api = StubQuicheApi()
            api.connTimeout = 7.seconds
            val driver = createTestDriver(api, keepAliveInterval = 10.seconds, clock = SimClock(testScheduler))
            driver.start(this)
            try {
                testScheduler.advanceTimeBy(7.seconds)
                runCurrent()
                assertEquals(1, api.onTimeoutCount, "the 7s quiche timer fire goes to quiche")
                assertEquals(0, api.ackElicitingCount, "no PING yet at t=7s")
                testScheduler.advanceTimeBy(3.seconds)
                runCurrent()
                assertEquals(1, api.ackElicitingCount, "keepalive must fire at t=10s — markNow tracks virtual elapsed time")
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun realClock_markNowSkew_documentsWhySimClockExists() =
        runTest {
            // The SAME scenario on RealDriverClock: its monotonic mark reads ~0 wall-clock elapsed at
            // every virtual wake, so the keepalive deadline never shrinks below the 7s quiche timer
            // and the PING never fires — three quiche wakes and zero PINGs by t=21s. This pins the
            // skew RealDriverClock has under virtual time; SimClock.markNow is what removes it.
            // (RealDriverClock's ARM side is fine under virtual time — W1 proved that — which is why
            // SimClock.armTimeout is the identical plain onTimeout clause.)
            val api = StubQuicheApi()
            api.connTimeout = 7.seconds
            val driver = createTestDriver(api, keepAliveInterval = 10.seconds, clock = RealDriverClock)
            driver.start(this)
            try {
                repeat(3) {
                    testScheduler.advanceTimeBy(7.seconds)
                    runCurrent()
                }
                assertEquals(3, api.onTimeoutCount, "every wake went to quiche")
                assertEquals(0, api.ackElicitingCount, "the keepalive PING is starved by the wall-clock mark's skew")
            } finally {
                driver.commands.close()
            }
        }

    private fun createTestDriver(
        api: StubQuicheApi,
        keepAliveInterval: Duration?,
        clock: DriverClock,
    ): QuicheDriver =
        QuicheDriver(
            api = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
            keepAliveInterval = keepAliveInterval,
            clock = clock,
            driverContext = EmptyCoroutineContext,
        )
}
