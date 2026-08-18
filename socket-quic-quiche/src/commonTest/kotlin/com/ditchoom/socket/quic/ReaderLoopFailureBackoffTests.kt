@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A permanently failing UDP receive must stop, not spin (#396).
 *
 * Both reader loops used to retry a failed `receive` with a bare `continue` — no delay, no bound. A
 * *persistent* socket error makes `selector.select()` return immediately every time, so the reader
 * span its dispatcher at full tilt for the rest of the connection's life. Android's netd
 * `SOCK_DESTROY` produces exactly that when a network goes away under a live socket, and combined
 * with a path that is never retired (#395) the dead path's reader was still running 101 minutes
 * later.
 *
 * The non-owned loop also leaked: it took a `recvBufPool` buffer *before* the throwing call and
 * released it only on the two typed `Closed` arms, so every escaping `IOException` dropped one
 * `MAX_DATAGRAM_SIZE` pooled buffer on the floor — at spin rate.
 *
 * [AlwaysFailingUdpChannel] stops throwing after [AlwaysFailingUdpChannel.ATTEMPT_CAP] so this test
 * *fails* rather than *hangs* against the unfixed driver. That matters: a hot spin with no
 * suspension point would otherwise make `advanceUntilIdle()` never return, and a hang is a much
 * worse regression signal than an assertion — see the thread-blocking-hang traps in this suite.
 */
class ReaderLoopFailureBackoffTests {
    /** Fails every receive the way a torn-down network does, then parks so the test can assert. */
    private class AlwaysFailingUdpChannel : UdpChannel {
        var receiveAttempts = 0
            private set

        override suspend fun receive(buffer: PlatformBuffer): Int {
            receiveAttempts++
            if (receiveAttempts >= ATTEMPT_CAP) {
                Channel<Unit>().receive() // park: the loop is provably unbounded by now
            }
            // The driver catches Exception broadly; the concrete type is irrelevant to the bound.
            // Stands in for ENETUNREACH / EHOSTUNREACH / ECONNABORTED / PortUnreachableException.
            throw IllegalStateException("simulated ENETUNREACH")
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            dest: PathKey?,
        ): SendOutcome = sendOutcomeOf { }

        override fun close() = Unit

        companion object {
            /** Far above the real bound, so exceeding it is unambiguous rather than borderline. */
            const val ATTEMPT_CAP = 1_000
        }
    }

    @Test
    fun aPermanentlyFailingReceiveStopsInsteadOfSpinningForever() =
        runTest {
            val tracking = TrackingBufferFactory()
            val udp = AlwaysFailingUdpChannel()
            val driver =
                QuicheDriver(
                    migration = MigrationCapability.BackendCannotMigrate,
                    rawApi = StubQuicheApi(),
                    conn = QuicheConn(1L),
                    bufferFactory = tracking,
                    recvInfo = QuicheRecvInfo(1L),
                    sendInfo = QuicheSendInfo(1L),
                    udpChannel = udp,
                    clientMode = true, // the reader loop is the protagonist
                    isServer = false,
                    clock = SimClock(testScheduler),
                    driverContext = EmptyCoroutineContext,
                )
            val simScope = CoroutineScope(coroutineContext + Job())
            driver.start(simScope)
            // Virtual time: every backoff delay elapses instantly, so a *bounded* retry settles here
            // while an unbounded one runs to ATTEMPT_CAP.
            testScheduler.advanceUntilIdle()

            assertTrue(
                udp.receiveAttempts < AlwaysFailingUdpChannel.ATTEMPT_CAP,
                "the reader retried ${udp.receiveAttempts} times without stopping — a permanently " +
                    "failing receive must give up, not spin the dispatcher for the connection's life (#396)",
            )

            driver.destroy()
            simScope.cancel()
            testScheduler.advanceUntilIdle()
            // The other half of #396: a buffer taken before the throwing call must be released on the
            // escaping-exception path too, not only on the two typed Closed arms.
            tracking.assertNoLeaks()
        }
}
