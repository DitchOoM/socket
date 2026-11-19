package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.EchoQuiche
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One pass of [Loop] as a function of its inputs and the time: a fake socket, a connection whose quiche echoes every
 * datagram, a clock that is a number.
 */
class LoopPassTests {
    @Test
    fun aPassEchoesEveryWaitingDatagramWithOneSend() {
        val rig = Rig()
        repeat(3) { rig.io.arrive(byteArrayOf(it.toByte(), 7), peer(it)) }

        rig.loop.pass(Nanos(0))

        assertEquals(1, rig.io.sendCalls)
        assertEquals(listOf(0, 1, 2).map { listOf(it, 7) }, rig.io.sent.map { it.bytes.map(Byte::toInt) })
        // One connection, one path: every reply goes to that path's peer. Per-sender replies are a server's (step 4).
        assertEquals(List(3) { SendTarget.ConnectedPeer }, rig.io.sent.map { it.target })
    }

    @Test
    fun aDeadlineFiresInThePassAtItsTimeAndNotBefore() {
        val rig = Rig()
        var fired = 0
        rig.loop.port.arm(Nanos(100), Runnable { fired++ })

        val next = rig.loop.pass(Nanos(50))
        assertEquals(0, fired)
        assertEquals(NextDue.at(100L), next)

        rig.loop.pass(Nanos(100))
        assertEquals(1, fired)
    }

    @Test
    fun submittedWorkRunsInTheNextPass() {
        val rig = Rig()
        var ran = 0
        rig.loop.port.submit(
            object : LoopWork() {
                override fun run(port: LoopPort) {
                    ran++
                }

                override fun gone(end: ConnEnd) = Unit
            },
        )
        assertEquals(0, ran)
        rig.loop.pass(Nanos(0))
        assertEquals(1, ran)
    }

    @Test
    fun aFullSocketKeepsTheDatagramForTheNextPass() {
        val rig = Rig()
        rig.io.arrive(byteArrayOf(1), peer(1))
        rig.io.refuseNext(UdpError.WouldBlock)

        rig.loop.pass(Nanos(0))
        assertEquals(0, rig.io.sent.size)

        rig.loop.pass(Nanos(1))
        assertEquals(listOf(listOf(1)), rig.io.sent.map { it.bytes.map(Byte::toInt) })
    }

    @Test
    fun anyOtherRefusalDropsThatDatagramOnly() {
        val rig = Rig()
        rig.io.arrive(byteArrayOf(1), peer(1))
        rig.io.arrive(byteArrayOf(2), peer(2))
        rig.io.refuseNext(UdpError.HostUnreachable)

        rig.loop.pass(Nanos(0))
        rig.loop.pass(Nanos(1))

        assertEquals(listOf(listOf(2)), rig.io.sent.map { it.bytes.map(Byte::toInt) })
        assertEquals(1L, rig.loop.counts.sendRefusals)
    }

    @Test
    fun outputLargerThanTheBatchIsSentInTheSamePass() {
        val rig = Rig(maxDatagrams = 2)
        repeat(5) { rig.io.arrive(byteArrayOf(it.toByte()), peer(it)) }

        rig.loop.pass(Nanos(0))

        assertEquals(5, rig.io.sent.size)
        assertEquals(3, rig.io.sendCalls)
    }

    @Test
    fun aFloodIsTakenAtMostOneBoundPerPass() {
        val rig = Rig()
        repeat(1500) { rig.io.arrive(byteArrayOf(1), peer(it)) }

        rig.loop.pass(Nanos(0))
        assertEquals(1024L, rig.loop.counts.datagramsIn)

        rig.loop.pass(Nanos(1))
        assertEquals(1500L, rig.loop.counts.datagramsIn)
    }

    @Test
    fun anEchoedDatagramCostsOneClockReadWhenQuicheReadsItAndNoneWhenItIsWritten() {
        // On a device whose clocksource is the HPET a read of the clock traps to the host: a pass reads it per datagram
        // only where the stamp needs it, when quiche reads a datagram; a write's datagrams share one reading.
        fun readsToEcho(datagrams: Int): Long {
            val clock = VirtualClock()
            val io = ScriptedIo()
            repeat(datagrams) { io.arrive(byteArrayOf(it.toByte()), peer(it)) }
            val rig = ConnRig(EchoQuiche(), io = io, clock = clock)
            clock.reads = 0
            rig.loop.pass(Nanos(0))
            assertEquals(datagrams, io.sent.size, "every datagram was echoed")
            return clock.reads
        }
        val one = readsToEcho(1)
        val twenty = readsToEcho(20)
        assertTrue(twenty - one <= 19, "clock reads: $one to echo 1 datagram, $twenty to echo 20")
    }

    @Test
    fun datagramsTheIoHoldsAreReadWithoutWaitingForAnotherOne() {
        // Whole messages waiting inside the io are invisible to the poller: a pass cut short by the per-pass bound must
        // leave the loop's next pass due at once, and that pass must finish the backlog with nothing new arriving.
        val rig = Rig()
        rig.io.holdsBacklog = true
        repeat(1500) { rig.io.arrive(byteArrayOf(1), peer(it)) }

        val next = rig.loop.pass(Nanos(0))
        assertEquals(1024L, rig.loop.counts.datagramsIn)
        assertEquals(NextDue.at(0L), next, "with datagrams held inside the io the loop must not sleep")

        val last = rig.loop.pass(Nanos(1))
        assertEquals(1500L, rig.loop.counts.datagramsIn, "the backlog is delivered with no new datagram")
        assertEquals(NextDue.None, last, "and once it is empty the loop may sleep")
    }

    @Test
    fun aLoopThatFailsEndsItsConnectionsAndAnswersLaterWorkAsGone() {
        val failure = IllegalStateException("planted")
        val rig = Rig()
        var gone = 0
        rig.loop.port.submit(
            object : LoopWork() {
                override fun run(port: LoopPort) = throw failure

                override fun gone(end: ConnEnd) = Unit
            },
        )

        rig.loop.run()

        val end =
            assertIs<ConnEnd.LoopFailed>(
                rig.conn.sink.ended
                    .single(),
                "the loop stopped and ended its connection, once",
            )
        assertSame(failure, end.thrown.throwable)
        rig.loop.port.submit(
            object : LoopWork() {
                override fun run(port: LoopPort) = Unit

                override fun gone(end: ConnEnd) {
                    gone++
                }
            },
        )
        assertEquals(1, gone, "work handed to a stopped loop is answered as gone")
    }

    private fun peer(n: Int) = PathKey(family = 4, port = 1000 + n, hi = 0L, lo = 0x7F000001L)
}

private class ScriptedIo : DatagramBatchIo {
    class Sent(
        val bytes: ByteArray,
        val target: SendTarget,
    )

    private class Arrival(
        val bytes: ByteArray,
        val from: PathKey,
    )

    override val capability: BatchCapability = BatchCapability.OneByOne
    private val arrivals = ArrayDeque<Arrival>()
    private val refusals = ArrayDeque<UdpError>()
    val sent = ArrayList<Sent>()
    var sendCalls = 0

    /** Answers [ReceiveBatchResult.GotWithBacklog] while arrivals remain, as an io holding whole messages does. */
    var holdsBacklog = false

    fun arrive(
        bytes: ByteArray,
        from: PathKey,
    ) = arrivals.addLast(Arrival(bytes, from))

    fun refuseNext(error: UdpError) = refusals.addLast(error)

    override fun send(batch: SendBatch): SendBatchResult {
        sendCalls++
        for (n in 0 until batch.pending) {
            if (refusals.isNotEmpty()) return SendBatchResult.Partial(n, refusals.removeFirst())
            val storage = batch.storage.owner
            storage.setLimit(batch.offsetOf(n) + batch.lengthOf(n))
            storage.position(batch.offsetOf(n))
            sent.add(Sent(storage.readByteArray(batch.lengthOf(n)), batch.targetOf(n)))
        }
        return SendBatchResult.AllTaken
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        var got = 0
        while (arrivals.isNotEmpty()) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            val arrival = arrivals.removeFirst()
            val memory = buffer.bytes.owner
            memory.resetForWrite()
            memory.writeBytes(arrival.bytes)
            into.received(buffer, arrival.bytes.size, arrival.from, ArrivedOn.Unreported)
            got++
        }
        return when {
            got == 0 -> ReceiveBatchResult.Nothing
            holdsBacklog && arrivals.isNotEmpty() -> ReceiveBatchResult.GotWithBacklog
            else -> ReceiveBatchResult.Got
        }
    }

    override fun probe(): SocketProbe = SocketProbe.Open

    override fun reopen(): Reopened = Reopened.SameEndpoint

    override fun moveTo(bind: com.ditchoom.socket.quic.PathKey): com.ditchoom.socket.quic.core.udp.Moved =
        com.ditchoom.socket.quic.core.udp.Moved.Unsupported

    override fun close(): SocketClosed = SocketClosed
}

private class Rig(
    maxDatagrams: Int = 64,
) {
    val io = ScriptedIo()
    val conn = ConnRig(EchoQuiche(), io = io, maxDatagrams = maxDatagrams)
    val loop = conn.loop
}
