package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quiche's pacing on the loop's clock: a datagram due later than the allowance is not flushed early, the connection
 * sends nothing more until then, and the loop wakes once for it.
 */
class ConnPacingTests {
    private val ms = 1_000_000L

    private companion object {
        /** Sixteen datagrams two milliseconds apart, the first at zero. */
        const val SCHEDULE_ENDS = 30_000_000L
    }

    @Test
    fun aDatagramDueLaterIsHeldUntilItsTimeAndTheLoopWakesOnceForIt() {
        val rig = Rig(dueTimes = longArrayOf(0, 0, 5 * ms, 6 * ms, 7 * ms))

        val first = rig.pass(now = 0)

        // The third datagram is already written when its time is read, so it leaves with the two due; no fourth is asked for.
        assertEquals(3, rig.sent, "two due datagrams and the first one that is not")
        assertEquals(3, rig.api.sendCalls)
        assertEquals(NextDue.at(5 * ms), first, "one deadline, at the held datagram's time")

        // Something touches the connection before then: it does not ask quiche for more.
        rig.conn.conn.submit(NoWork)
        val second = rig.pass(now = 3 * ms)
        assertEquals(3, rig.api.sendCalls, "quiche is not asked before the pacer's time")
        assertEquals(3, rig.sent)
        assertEquals(NextDue.at(5 * ms), second, "still the one deadline")
    }

    @Test
    fun theDeadlineReleasesWhatIsDueWithinTheAllowanceAndHoldsAgain() {
        val rig = Rig(dueTimes = longArrayOf(0, 0, 5 * ms, 6 * ms, 7 * ms))
        rig.pass(now = 0)

        val next = rig.pass(now = 5 * ms)

        // Due at 6 ms is within the 1 ms allowance of 5 ms: it goes. The one due at 7 ms is the next held one.
        assertEquals(5, rig.sent)
        assertEquals(5, rig.api.sendCalls)
        assertEquals(NextDue.at(7 * ms), next)
    }

    @Test
    fun aTimeThatIsNotPacingIsNotObeyed() {
        // A reading of some other clock, hours ahead: the datagrams go at once.
        val rig = Rig(dueTimes = longArrayOf(3_600_000 * ms, 3_600_000 * ms, 3_600_000 * ms))

        rig.pass(now = 0)

        assertEquals(3, rig.sent)
        assertEquals(
            NextDue.None,
            rig.conn.loop.port
                .nextDue(Nanos(0)),
        )
    }

    /**
     * A loop whose timed waits wake only every 16 ms (the JDK selector on Windows) and quiche pacing 2 ms apart from
     * each datagram it actually sent. With a 1 ms allowance the loop slept a whole tick for each held datagram, so a
     * paced flight crawled at two datagrams per tick: the thousand-connection echo on Windows connected 134 in 13 s, in
     * 420 passes. A release within the wait's resolution goes at once.
     */
    @Test
    fun aLoopThatWakesOnlyEverySixteenMillisecondsStillSendsAPacedFlightWithinOneTickOfItsSchedule() {
        val tick = 16 * ms
        val datagrams = 16
        val api = AnchoredApi(StubQuicheApi(), interval = 2 * ms)
        val io = FakeIo()
        var sent = 0
        io.onSend = { pending ->
            sent += pending
            SendBatchResult.AllTaken
        }
        val rig = ConnRig(api, rules = SlotRules.forWaits(WaitResolution(tick)), io = io)
        api.clock = { rig.clock.at.value }
        repeat(datagrams) { api.stub.connSendQueue.addLast(1200) }
        var now = 0L
        while (now <= SCHEDULE_ENDS + 2 * tick) {
            val due = rig.pass(now)
            if (sent == datagrams) break
            now = due.fold({ now + tick }, { at -> maxOf(now + tick, ((at + tick - 1) / tick) * tick) })
        }
        assertEquals(datagrams, sent, "every datagram left by ${now / ms} ms")
        assertTrue(now <= SCHEDULE_ENDS + tick, "the flight scheduled to end at ${SCHEDULE_ENDS / ms} ms left by ${now / ms} ms")
    }

    private object NoWork : ConnWork() {
        override fun run(inside: Inside) = Unit

        override fun gone(end: ConnEnd) = Unit
    }

    private class Rig(
        dueTimes: LongArray,
    ) {
        val api = PacedApi(StubQuicheApi(), dueTimes)
        private val io = FakeIo()
        var sent = 0
        val conn = ConnRig(api, io = io)

        init {
            io.onSend = { pending ->
                sent += pending
                SendBatchResult.AllTaken
            }
            repeat(dueTimes.size) { api.stub.connSendQueue.addLast(1200) }
        }

        fun pass(now: Long): NextDue = conn.pass(now)
    }

    /** quiche's pacer as it behaves: each datagram is due an interval after the later of the last one's time and now. */
    private class AnchoredApi(
        val stub: StubQuicheApi,
        private val interval: Long,
    ) : QuicheApi by stub {
        var clock: () -> Long = { 0L }
        private var last = Long.MIN_VALUE

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long {
            val at = if (last == Long.MIN_VALUE) clock() else maxOf(last + interval, clock())
            last = at
            return at
        }
    }

    /** The stub, with the pacer's time of each datagram scripted in the order quiche writes them. */
    private class PacedApi(
        val stub: StubQuicheApi,
        private val dueTimes: LongArray,
    ) : QuicheApi by stub {
        var sendCalls = 0
        private var written = 0

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            val length = stub.connSend(conn, buf, bufLen, sendInfo)
            if (length > 0) sendCalls++
            return length
        }

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = dueTimes[written++.coerceAtMost(dueTimes.size - 1)]
    }
}
