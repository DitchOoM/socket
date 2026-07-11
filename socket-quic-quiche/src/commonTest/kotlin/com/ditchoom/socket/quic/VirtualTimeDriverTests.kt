package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * W1 of RFC_DETERMINISTIC_SIMULATION.md (§3.1): the driver's `driverContext` seam. The control loop
 * used to hardwire `Dispatchers.Default`, keeping [DriverClock.armTimeout] wakes off any virtual-time
 * scheduler; passing [EmptyCoroutineContext] now inherits the caller's dispatcher, so the whole loop
 * runs on the kotlinx-coroutines-test scheduler under [runTest]. The existing [ReactiveDriverTests]
 * cover the real-dispatcher (`runQuicTest`) path; these specifically prove the virtual-time path.
 */
class VirtualTimeDriverTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun virtualTime_manualClock_advancesKeepAliveDeterministically() =
        runTest {
            // ManualDriverClock on the single-threaded virtual scheduler: each advance() is exactly one
            // keepalive PING, with no real dispatcher involved anywhere in the driver.
            val api = StubQuicheApi()
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = clock)
            driver.start(this)
            try {
                clock.advance(1.seconds)
                assertEquals(1, api.ackElicitingCount, "first keepalive deadline did not PING on the virtual scheduler")
                clock.advance(1.seconds)
                assertEquals(2, api.ackElicitingCount, "second keepalive deadline did not PING on the virtual scheduler")
                assertEquals(0, api.onTimeoutCount, "keepalive deadlines must not be handed to quiche as idle timeouts")
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun virtualTime_realClock_keepAliveRunsOnVirtualTime() =
        runTest {
            // The sharper proof: with the production RealDriverClock, the keepalive `onTimeout` itself is
            // a virtual-time delay — advancing the test scheduler by the interval fires the PING with no
            // wall-clock waiting. Pre-seam (hardwired Dispatchers.Default) this timer armed on a real
            // dispatcher and this test would need a real 1-second wait per PING.
            val api = StubQuicheApi()
            val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = RealDriverClock)
            driver.start(this)
            try {
                runCurrent() // let the loop start and park with its keepalive timer armed
                assertEquals(0, api.ackElicitingCount, "no PING before the keepalive interval elapses")
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(1, api.ackElicitingCount, "keepalive PING must fire at virtual t+1s")
                testScheduler.advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(2, api.ackElicitingCount, "keepalive PING must fire again at virtual t+2s")
            } finally {
                driver.commands.close()
            }
        }

    /** [ReactiveDriverTests.createTestDriver] wiring, with the loops inheriting the caller's (virtual-time) dispatcher. */
    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        keepAliveInterval: Duration? = null,
        clock: DriverClock = RealDriverClock,
        driverContext: CoroutineContext = EmptyCoroutineContext,
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
            driverContext = driverContext,
        )
}
