package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopWait
import com.ditchoom.socket.quic.core.loop.WakeCause
import com.ditchoom.socket.quic.core.loop.WriteInterest
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Quiche's pacing on the loop's clock: a datagram due later than the allowance is not flushed early, the connection
 * sends nothing more until then, and the loop wakes once for it.
 */
class ConnPacingTests {
    private val ms = 1_000_000L

    @Test
    fun aDatagramDueLaterIsHeldUntilItsTimeAndTheLoopWakesOnceForIt() {
        val rig = Rig(dueTimes = longArrayOf(0, 0, 5 * ms, 6 * ms, 7 * ms))

        val first = rig.loop.pass(now = 0)

        // The third datagram is already written when its time is read, so it leaves with the two due; no fourth is asked for.
        assertEquals(3, rig.io.sent, "two due datagrams and the first one that is not")
        assertEquals(3, rig.api.sendCalls)
        assertEquals(NextDue.at(5 * ms), first, "one deadline, at the held datagram's time")

        // Something touches the connection before then: it does not ask quiche for more.
        rig.now = 3 * ms
        rig.conn.submit(NoWork)
        val second = rig.loop.pass(now = 3 * ms)
        assertEquals(3, rig.api.sendCalls, "quiche is not asked before the pacer's time")
        assertEquals(3, rig.io.sent)
        assertEquals(NextDue.at(5 * ms), second, "still the one deadline")
    }

    @Test
    fun theDeadlineReleasesWhatIsDueWithinTheAllowanceAndHoldsAgain() {
        val rig = Rig(dueTimes = longArrayOf(0, 0, 5 * ms, 6 * ms, 7 * ms))
        rig.loop.pass(now = 0)

        rig.now = 5 * ms
        val next = rig.loop.pass(now = 5 * ms)

        // Due at 6 ms is within the 1 ms allowance of 5 ms: it goes. The one due at 7 ms is the next held one.
        assertEquals(5, rig.io.sent)
        assertEquals(5, rig.api.sendCalls)
        assertEquals(NextDue.at(7 * ms), next)
    }

    @Test
    fun aTimeThatIsNotPacingIsNotObeyed() {
        // A reading of some other clock, hours ahead: the datagrams go at once.
        val rig = Rig(dueTimes = longArrayOf(3_600_000 * ms, 3_600_000 * ms, 3_600_000 * ms))

        rig.loop.pass(now = 0)

        assertEquals(3, rig.io.sent)
        assertEquals(NextDue.None, rig.loop.deadlines.nextDue(0))
    }

    private object NoWork : ConnWork() {
        override fun run(inside: Conn.Inside) = Unit

        override fun gone() = Unit
    }

    private inner class Rig(
        dueTimes: LongArray,
    ) {
        var now = 0L
        val io = CountingIo()
        val api = PacedApi(StubQuicheApi(), dueTimes)
        val conn: Conn
        val loop: Loop

        init {
            val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
            val factory = BufferFactory.managed()
            val parts = OneConnParts()
            val send = SendBatch(factory.allocate(limits.maxDatagrams * limits.maxDatagramSize), address = 0L, limits)
            loop =
                Loop(
                    wait = NeverWaits,
                    clock = { now },
                    io = io,
                    parts = parts,
                    receive =
                        ReceiveBatch(
                            FixedPool(limits.maxDatagrams) { ReceiveBuffer(it, factory.allocate(1500), address = 0L) },
                            limits,
                        ),
                    send = send,
                )
            repeat(dueTimes.size) { api.stub.connSendQueue.addLast(1200) }
            conn =
                Conn(
                    api,
                    QuicheConn(1L),
                    ConnPath(api.recvInfoNew(0L, 0, 0L, 0), api.sendInfoNew(), SendTarget.ConnectedPeer),
                    loop,
                    Entrant.loopTurn(loop = 1, generation = 1),
                    { now },
                    object : ConnEvents {
                        override fun established(inside: Conn.Inside) = Unit

                        override fun readable(
                            inside: Conn.Inside,
                            stream: QuicStreamId,
                        ) = Unit

                        override fun writable(
                            inside: Conn.Inside,
                            stream: QuicStreamId,
                        ) = Unit

                        override fun closed(end: ConnEnd) = Unit

                        override fun served(inside: Conn.Inside) = Unit
                    },
                    factory,
                    DatagramSlots.None,
                )
            parts.attach(conn)
            conn.start()
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

    private class CountingIo : DatagramBatchIo {
        override val capability: BatchCapability = BatchCapability.OneByOne
        var sent = 0

        override fun send(batch: SendBatch): SendBatchResult {
            sent += batch.pending
            return SendBatchResult.AllTaken
        }

        override fun receive(into: ReceiveBatch): ReceiveBatchResult = ReceiveBatchResult.Nothing

        override fun close(): SocketClosed = SocketClosed
    }

    private object NeverWaits : LoopWait {
        override fun await(
            until: NextDue,
            write: WriteInterest,
        ): WakeCause = WakeCause.Woken

        override fun wake() = Unit

        override fun close() = Unit
    }
}
