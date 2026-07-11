@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim.fuzz

import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.TrackingBufferFactory
import com.ditchoom.socket.quic.sim.SimClock
import com.ditchoom.socket.quic.sim.SimTrace
import com.ditchoom.socket.quic.sim.TimelineUdpChannel
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.runQuicSim
import com.ditchoom.socket.quic.sim.simFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the two reader-loop buffer leaks the W5 timeline fuzzer found on its first
 * run (RFC_DETERMINISTIC_SIMULATION.md §6 — fuzz findings become committed fixtures). Both leaked
 * one `recvBufPool` buffer per connection at the **leaf** — a real native-memory leak under the
 * explicit-free `BufferFactory.network()`/`deterministic()` factories QUIC uses on every platform:
 *
 * 1. **Idle-close pool clear vs parked reader** (shrunk by the fuzzer to an EMPTY timeline — seed 1,
 *    keepalive off, 30 s idle close): `cleanup()` cleared `recvBufPool` while the primary
 *    `udpReaderLoop` was still parked in `receive()` holding a pool buffer; the reader's later
 *    cancellation-free re-pooled the buffer into the already-cleared pool (BufferPool has no closed
 *    state), so its leaf allocation was never freed. Fixed by cancel-and-joining ALL reader loops
 *    in `run()`'s finally BEFORE `cleanup()`.
 *
 * 2. **Datagram racing the close**: a datagram received after `commands.close()` made the reader's
 *    `commands.send` throw `ClosedSendChannelException`, dropping the in-flight buffer without a
 *    free (it never reached the driver, so `cleanup()`'s drain could not release it). Fixed by
 *    freeing the buffer at that throw site in `udpReaderLoop`.
 */
class ReaderLoopCloseRaceRegressionTests {
    /** The fuzzer's shrunk finding, verbatim: no events at all — the idle close alone leaked. */
    private val shrunkIdleCloseFixture =
        simFixture("shrunk-idle-close-reader-leak") {
            runFor(SIM_IDLE_TIMEOUT + 1.seconds)
        }

    @Test
    fun idleClose_readerPoolBufferIsFreedAtTheLeaf() =
        runTest {
            val tracking = TrackingBufferFactory()
            runQuicSim(
                fixture = shrunkIdleCloseFixture,
                keepAliveInterval = null,
                clientMode = true, // the parked reader loop is the leak's protagonist
                bufferFactory = tracking,
            ) {
                connTimeout = SIM_IDLE_TIMEOUT
                closeOnTimeout = true
                timedOut = true
            }
            tracking.assertNoLeaks()
        }

    @Test
    fun datagramRacingClose_readerFreesTheInFlightBuffer() =
        runTest {
            val tracking = TrackingBufferFactory()
            val trace = SimTrace { Duration.ZERO }
            val udp = TimelineUdpChannel(trace)
            val driver =
                QuicheDriver(
                    api = StubQuicheApi(),
                    conn = QuicheConn(1L),
                    bufferFactory = tracking,
                    recvInfo = QuicheRecvInfo(1L),
                    sendInfo = QuicheSendInfo(1L),
                    udpChannel = udp,
                    clientMode = true,
                    isServer = false,
                    clock = SimClock(testScheduler),
                    driverContext = EmptyCoroutineContext,
                )
            val simScope = CoroutineScope(coroutineContext + Job())
            driver.start(simScope)
            testScheduler.runCurrent() // reader parked in receive() holding a pool buffer

            // The race, pinned deterministically: the delivery resumes the parked reader (task
            // queued first), the close lands before that task runs — so the reader wakes holding
            // a datagram for a command channel that is already closed.
            udp.deliver("0102030405")
            driver.commands.close()
            testScheduler.advanceUntilIdle()

            driver.destroy()
            simScope.cancel()
            testScheduler.advanceUntilIdle()
            tracking.assertNoLeaks()
        }
}
