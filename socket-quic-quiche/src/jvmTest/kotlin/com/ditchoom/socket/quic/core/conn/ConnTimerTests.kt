package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import kotlinx.coroutines.Runnable
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ConnTimerTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val ms = 1_000_000L
    private val api = StubQuicheApi()
    private val port = LoopPort(FakePoller())
    private val timer = ConnTimer(QuicheConnRef(api, QuicheConn(1L)), port, WakeSink())
    private var fired = 0
    private val fire = Runnable { fired++ }

    @Test
    fun theDeadlineIsTheEarlierOfQuichesTimeoutAndWhatWasOffered() {
        api.connTimeout = 10.milliseconds
        timer.sink.offer(Nanos(4 * ms))
        timer.rearm(Nanos(0), fire)
        assertEquals(NextDue.at(4 * ms), port.nextDue(Nanos(0)))
    }

    @Test
    fun aDeadlineThatIsAlreadyEarlierIsKept() {
        api.connTimeout = 5.milliseconds
        timer.rearm(Nanos(0), fire)
        api.connTimeout = 9.milliseconds
        timer.rearm(Nanos(0), fire)
        port.fireDue(Nanos(5 * ms))
        assertEquals(1, fired)
        assertEquals(NextDue.None, port.nextDue(Nanos(5 * ms)))
    }

    @Test
    fun nothingToWaitForArmsNothingAndExpiryRunsQuichesTimeout() {
        timer.rearm(Nanos(0), fire)
        assertEquals(NextDue.None, port.nextDue(Nanos(0)))
        timer.expire()
        assertEquals(1, api.onTimeoutCount)
    }

    @Test
    fun aNudgeAsksQuicheForThePtoProbesNow() {
        timer.nudge()
        assertEquals(1, api.probeNowCount)
    }
}
