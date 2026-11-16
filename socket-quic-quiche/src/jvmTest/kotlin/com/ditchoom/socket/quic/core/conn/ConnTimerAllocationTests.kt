package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import kotlinx.coroutines.Runnable
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A connection's timer re-armed the ways a busy connection re-arms it: moved earlier by an offered wake, fired and armed
 * again, moved later, disarmed and armed again. Each of those allocates nothing, so how often the loop re-arms (which
 * is how fast the host is) adds nothing to its bytes per echo.
 */
class ConnTimerAllocationTests {
    private val api = StubQuicheApi()
    private val port = LoopPort(FakePoller())
    private val timer = ConnTimer(QuicheConnRef(api, QuicheConn(1L)), port, WakeSink())
    private var fired = 0
    private val fire = Runnable { fired++ }

    @Test
    fun reArmingTheConnectionsTimerAllocatesNothing() {
        api.connTimeout = 25.milliseconds
        var now = 0L

        fun cycle() {
            // quiche's timer, then an earlier wake offered: the deadline moves earlier.
            timer.rearm(Nanos(now), fire)
            timer.sink.offer(Nanos(now + MS))
            timer.rearm(Nanos(now), fire)
            port.nextDue(Nanos(now))
            // It falls due and fires; the turn after arms it again.
            now += MS
            port.fireDue(Nanos(now))
            timer.expire()
            timer.rearm(Nanos(now), fire)
            // The ended connection's linger arms a later time alone, then nothing.
            timer.sink.offer(Nanos(now + 40 * MS))
            timer.armOffered(fire)
            port.nextDue(Nanos(now))
            timer.disarm()
            port.fireDue(Nanos(now))
        }
        repeat(WARM_UP) { cycle() }
        val firedBefore = fired
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val self = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(self)
        repeat(CYCLES) { cycle() }
        val perCycle = (threads.getThreadAllocatedBytes(self) - before).toDouble() / CYCLES
        println("[core timer re-arm allocation] %.2f B per cycle of five re-arms".format(perCycle))
        assertEquals(CYCLES, fired - firedBefore, "every cycle's deadline fired once")
        assertTrue(perCycle < 1.0, "re-arming the connection's timer allocated %.2f B per cycle".format(perCycle))
    }

    private companion object {
        const val MS = 1_000_000L
        const val WARM_UP = 20_000
        const val CYCLES = 20_000
    }
}
