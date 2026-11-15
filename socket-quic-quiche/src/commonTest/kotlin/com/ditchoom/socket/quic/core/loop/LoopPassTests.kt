package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** One pass of [Loop] as a function of its inputs and the time: a fake socket, an echo, a clock that is a number. */
class LoopPassTests {
    @Test
    fun aPassEchoesEveryWaitingDatagramWithOneSend() {
        val rig = Rig()
        repeat(3) { rig.io.arrive(byteArrayOf(it.toByte(), 7), peer(it)) }

        rig.loop.pass(now = 0)

        assertEquals(1, rig.io.sendCalls)
        assertEquals(listOf(0, 1, 2).map { listOf(it, 7) }, rig.io.sent.map { it.bytes.map(Byte::toInt) })
        assertEquals(listOf(peer(0), peer(1), peer(2)), rig.io.sent.map { (it.target as SendTarget.ServerReply).to })
    }

    @Test
    fun aDeadlineFiresInThePassAtItsTimeAndNotBefore() {
        val rig = Rig()
        var fired = 0
        rig.loop.deadlines.arm(100, Runnable { fired++ })

        val next = rig.loop.pass(now = 50)
        assertEquals(0, fired)
        assertEquals(100L, assertIs<NextDue.At>(next).nanos)

        rig.loop.pass(now = 100)
        assertEquals(1, fired)
    }

    @Test
    fun submittedWorkRunsInTheNextPass() {
        val rig = Rig()
        var ran = 0
        rig.loop.submit(
            object : LoopWork() {
                override fun run(loop: Loop) {
                    ran++
                }
            },
        )
        assertEquals(0, ran)
        rig.loop.pass(now = 0)
        assertEquals(1, ran)
    }

    @Test
    fun aFullSocketKeepsTheDatagramForTheNextPass() {
        val rig = Rig()
        rig.io.arrive(byteArrayOf(1), peer(1))
        rig.io.refuseNext(UdpError.WouldBlock)

        rig.loop.pass(now = 0)
        assertEquals(0, rig.io.sent.size)

        rig.loop.pass(now = 1)
        assertEquals(listOf(listOf(1)), rig.io.sent.map { it.bytes.map(Byte::toInt) })
    }

    @Test
    fun anyOtherRefusalDropsThatDatagramOnly() {
        val rig = Rig()
        rig.io.arrive(byteArrayOf(1), peer(1))
        rig.io.arrive(byteArrayOf(2), peer(2))
        rig.io.refuseNext(UdpError.HostUnreachable)

        rig.loop.pass(now = 0)
        rig.loop.pass(now = 1)

        assertEquals(listOf(listOf(2)), rig.io.sent.map { it.bytes.map(Byte::toInt) })
        assertEquals(1L, rig.loop.counts.sendRefusals)
    }

    @Test
    fun outputLargerThanTheBatchIsSentInTheSamePass() {
        val rig = Rig(maxDatagrams = 2)
        repeat(5) { rig.io.arrive(byteArrayOf(it.toByte()), peer(it)) }

        rig.loop.pass(now = 0)

        assertEquals(5, rig.io.sent.size)
        assertEquals(3, rig.io.sendCalls)
    }

    @Test
    fun aFloodIsTakenAtMostOneBoundPerPass() {
        val rig = Rig()
        repeat(1500) { rig.io.arrive(byteArrayOf(1), peer(it)) }

        rig.loop.pass(now = 0)
        assertEquals(1024L, rig.loop.counts.datagramsIn)

        rig.loop.pass(now = 1)
        assertEquals(1500L, rig.loop.counts.datagramsIn)
    }

    private fun peer(n: Int) = PathKey(family = 4, port = 1000 + n, hi = 0L, lo = 0x7F000001L)
}

/** Answers every datagram with its own bytes, to its sender. */
internal class EchoParts : LoopParts {
    private class Waiting(
        val bytes: PlatformBuffer,
        val to: PathKey,
    )

    private val waiting = ArrayDeque<Waiting>()

    override fun onDatagram(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ) {
        val copy = BufferFactory.managed().allocate(length)
        buffer.buffer.position(0)
        buffer.buffer.setLimit(length)
        copy.write(buffer.buffer)
        copy.resetForRead()
        waiting.addLast(Waiting(copy, from))
    }

    override fun collect(out: SendBatch): Collected {
        while (waiting.isNotEmpty()) {
            if (out.room() === Room.Full) return Collected.MoreWaiting
            val next = waiting.removeFirst()
            val length = next.bytes.remaining()
            out.storage.position(out.nextOffset())
            out.storage.setLimit(out.storage.capacity)
            out.storage.write(next.bytes)
            out.commit(length, SendTarget.ServerReply(next.to, ReplySource.Undecodable))
        }
        return Collected.Everything
    }
}

private class FakeIo : DatagramBatchIo {
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

    fun arrive(
        bytes: ByteArray,
        from: PathKey,
    ) = arrivals.addLast(Arrival(bytes, from))

    fun refuseNext(error: UdpError) = refusals.addLast(error)

    override fun send(batch: SendBatch): SendBatchResult {
        sendCalls++
        for (n in 0 until batch.pending) {
            if (refusals.isNotEmpty()) return SendBatchResult.Partial(n, refusals.removeFirst())
            batch.storage.setLimit(batch.offsetOf(n) + batch.lengthOf(n))
            batch.storage.position(batch.offsetOf(n))
            sent.add(Sent(batch.storage.readByteArray(batch.lengthOf(n)), batch.targetOf(n)))
        }
        return SendBatchResult.AllTaken
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        var got = 0
        while (arrivals.isNotEmpty()) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            val arrival = arrivals.removeFirst()
            buffer.buffer.resetForWrite()
            buffer.buffer.writeBytes(arrival.bytes)
            into.received(buffer, arrival.bytes.size, arrival.from, ArrivedOn.Unreported)
            got++
        }
        return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
    }

    override fun close(): SocketClosed = SocketClosed
}

private class Rig(
    maxDatagrams: Int = 64,
) {
    val io = FakeIo()
    private val limits = BatchLimits(maxDatagrams = maxDatagrams, maxDatagramSize = 1500)
    val loop =
        Loop(
            wait = NeverWaits,
            clock = { 0L },
            io = io,
            parts = EchoParts(),
            receive =
                ReceiveBatch(
                    FixedPool(maxDatagrams) { ReceiveBuffer(it, BufferFactory.managed().allocate(1500), address = 0L) },
                    limits,
                ),
            send = SendBatch(BufferFactory.managed().allocate(maxDatagrams * 1500), address = 0L, limits),
        )
}

private object NeverWaits : LoopWait {
    override fun await(
        until: NextDue,
        write: WriteInterest,
    ): WakeCause = WakeCause.Woken

    override fun wake() = Unit

    override fun close() = Unit
}
