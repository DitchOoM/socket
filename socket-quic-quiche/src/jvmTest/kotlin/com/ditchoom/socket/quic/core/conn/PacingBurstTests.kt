package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.demux.ServerRig
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * quiche's pacer as it paces an application-limited connection, modelled by the api: each datagram is stamped with when
 * the next may leave, its own size at a low rate after the later of now and the previous stamp. A quiet connection's
 * short burst leaves at once, on a client and on a server; past the burst, and on a connection that keeps sending,
 * datagrams leave on quiche's times exactly as they did without one.
 */
class PacingBurstTests {
    @Test
    fun everySmallWriteOfABurstOnAQuietClientLeavesAtOnce() {
        val rig = ClientRig()
        rig.write(HANDSHAKE)
        rig.pass(0)

        // A quiet round trip later the application writes three small writes back to back, each in its own turn.
        repeat(3) {
            rig.write(SMALL)
            rig.pass(RTT)
        }

        assertEquals(listOf(0L, RTT, RTT, RTT), rig.sentAt, "each write left when it was written")
    }

    @Test
    fun everySmallWriteOfABurstOnAQuietServerLeavesAtOnce() {
        val api = AppLimitedQuiche(StubQuicheApi(), SLOW_NANOS_PER_BYTE)
        val rig = ServerRig(api, clock = VirtualClock(base = ClockBase.Libquiche))
        api.clock = { rig.clock.at.value }
        api.stub.connSendQueue.addLast(HANDSHAKE)
        rig.io.arrive(ServerRig.initial(Random(1).nextBytes(8)), ServerRig.peer(1))
        rig.pass(0)
        assertEquals(1, rig.io.sent.size, "the server answered the Initial")

        repeat(3) {
            api.stub.connSendQueue.addLast(SMALL)
            rig.conns.single().submit(Touch)
            rig.pass(RTT)
        }

        assertEquals(4, rig.io.sent.size, "each write left when it was written")
        assertEquals(emptyList(), rig.sinks.single().ended)
    }

    /** A burst is [Pacer.BURST_DATAGRAMS] datagrams ahead of quiche's times at most; the next waits for its time. */
    @Test
    fun theCreditIsSpentAfterABurstAndRestoredAfterQuiet() {
        val rig = ClientRig()
        rig.write(HANDSHAKE)
        rig.pass(0)

        val burst = Pacer.BURST_DATAGRAMS + 3
        repeat(burst) { rig.write(SMALL) }
        rig.pass(RTT)
        // The first is due now, the next ten go ahead of their times, the last of them stopping the write.
        assertEquals(1 + 1 + Pacer.BURST_DATAGRAMS, rig.sentAt.size)

        rig.drive(until = 3 * RTT)
        assertEquals(burst + 1, rig.sentAt.size, "the rest left on quiche's times")
        assertEquals(List(1 + Pacer.BURST_DATAGRAMS) { RTT }, rig.sentAt.subList(1, 2 + Pacer.BURST_DATAGRAMS))

        // Quiet again, and quiche's times have passed: a new burst leaves at once.
        repeat(3) {
            rig.write(SMALL)
            rig.pass(4 * RTT)
        }
        assertEquals(List(3) { 4 * RTT }, rig.sentAt.takeLast(3))
    }

    /**
     * A long transfer paced by quiche: after the burst every datagram leaves exactly when it would on a connection that
     * never went quiet, which has no burst at all: when the hold of the datagram before it ends.
     */
    @Test
    fun aLongTransfersSpacingPastTheBurstIsQuichesExactly() {
        val quiet = transfer(goesQuietFirst = true)
        val busy = transfer(goesQuietFirst = false)

        val first = quiet.first()
        assertEquals(List(1 + Pacer.BURST_DATAGRAMS) { first }, quiet.take(1 + Pacer.BURST_DATAGRAMS), "the burst left at once")
        assertEquals(List(2) { busy.first() }, busy.take(2), "without a burst only the datagram that stops the write goes early")
        for (k in 1 + Pacer.BURST_DATAGRAMS until TRANSFER) {
            assertEquals(quiet[k] - first, busy[k] - busy.first(), "datagram $k left on quiche's time")
        }
        for (k in 1 until TRANSFER) {
            assertEquals(INTERVAL * (k - 1), busy[k] - busy.first(), "datagram $k left when the hold of the one before it ended")
        }
    }

    /** The send times of a [TRANSFER]-datagram transfer, from its first datagram, written at once into quiche. */
    private fun transfer(goesQuietFirst: Boolean): List<Long> {
        val rig = ClientRig(nanosPerByte = INTERVAL / BULK)
        if (goesQuietFirst) {
            rig.write(HANDSHAKE)
            rig.pass(0)
        }
        val from = rig.sentAt.size
        repeat(TRANSFER) { rig.write(BULK) }
        rig.pass(RTT)
        rig.drive(until = RTT + TRANSFER * INTERVAL * 2)
        return rig.sentAt.drop(from).also { assertEquals(TRANSFER, it.size) }
    }

    /** A client connection over [AppLimitedQuiche], on a clock standing in for libquiche's, recording when each datagram left. */
    private class ClientRig(
        nanosPerByte: Long = SLOW_NANOS_PER_BYTE,
    ) {
        val api = AppLimitedQuiche(StubQuicheApi(), nanosPerByte)
        private val io = FakeIo()
        val conn = ConnRig(api, io = io, clock = VirtualClock(base = ClockBase.Libquiche))
        val sentAt = ArrayList<Long>()

        init {
            api.clock = { conn.clock.at.value }
            io.onSend = { pending ->
                repeat(pending) { sentAt += conn.clock.at.value }
                SendBatchResult.AllTaken
            }
        }

        /** quiche has one more datagram of [size] bytes to send, and the connection is served for it. */
        fun write(size: Int) {
            api.stub.connSendQueue.addLast(size)
            conn.conn.submit(Touch)
        }

        fun pass(at: Long) {
            conn.pass(at)
        }

        /** Passes at each time the connection asks to be woken, until [until]. */
        fun drive(until: Long) {
            var now = conn.clock.at.value
            while (now < until) {
                now = conn.pass(now).fold({ until }, { maxOf(it, now + 1) })
            }
        }
    }

    private object Touch : ConnWork() {
        override fun run(inside: Inside) = Unit

        override fun gone(end: ConnEnd) = Unit
    }

    /**
     * quiche's BBR2 pacer on an application-limited connection whose burst tokens are spent. quiche stamps a datagram
     * after its own `on_packet_sent`, so the time it gives is when the NEXT datagram may leave: this one's size at
     * [nanosPerByte] after the later of now and the previous such time. The connection's first datagram is stamped now,
     * as quiche's pacer releases at once before it has sent anything.
     */
    private class AppLimitedQuiche(
        val stub: StubQuicheApi,
        private val nanosPerByte: Long,
    ) : QuicheApi by stub {
        var clock: () -> Long = { 0L }
        private var due = 0L
        private var sent = 0

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int =
            stub.connSend(conn, buf, bufLen, sendInfo).also { written ->
                if (written > 0) {
                    due = if (sent++ == 0) clock() else maxOf(clock(), due) + written * nanosPerByte
                }
            }

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = due
    }

    private companion object {
        const val MS = 1_000_000L
        const val RTT = 60 * MS
        const val HANDSHAKE = 1_200
        const val SMALL = 551

        /** About 75 kB/s: one small datagram a round trip, times BBR2's startup gain. A small datagram is due ~7 ms after the last. */
        const val SLOW_NANOS_PER_BYTE = 13_300L
        const val BULK = 1_000
        const val INTERVAL = 2 * MS
        const val TRANSFER = 60
    }
}
