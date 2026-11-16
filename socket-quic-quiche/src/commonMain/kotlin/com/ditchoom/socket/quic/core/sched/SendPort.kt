package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.CallerCounts
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SendFault
import com.ditchoom.socket.quic.core.udp.SocketErrors
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.UdpError

/** How a flush ended. */
internal enum class Flushed {
    Sent,

    /** The socket is full: wait until it is writable. */
    Full,

    /** The socket died; the rest was dropped. */
    Dead,
}

private enum class Who { Loop, Caller }

/**
 * The any-thread face of a socket. Every send and receive failure goes through [SocketErrors] here, so a refusal
 * means one thing on every path: a full socket waits, a datagram's own error drops that datagram, and a socket's
 * end moves its health to Dead once and hands the loop one preallocated [died] work.
 *
 * Threads other than the loop send in place from a lane of their own ([takeLane], [sendInPlace]); when the socket
 * is full the lane goes to the loop, which sends it from [flushWaiting]. The loop uses [flush], [flushWaiting],
 * [adopt] and [receiveFailed]; every other function is for any thread.
 */
internal class SendPort(
    private val io: DatagramBatchIo,
    private val port: LoopPort,
    private val lanes: CallerLanes,
    private val died: LoopWork,
    private val loopCounts: LoopCounts,
    private val callerCounts: CallerCounts,
    private val errors: SocketErrors = SocketErrors(),
) {
    private val health = HealthCell()
    private val waiting = WaitingLanes()
    private val handOffs: Array<AdoptLane> =
        when (lanes) {
            is CallerLanes.None -> emptyArray()
            is CallerLanes.Of -> Array(lanes.pool.size) { AdoptLane(this, lanes.pool[it]) }
        }

    fun health(): SocketHealth = health.view()

    /** A lane for a thread that is not the loop to send from, or [Taken.Empty]: then the loop does the sending. */
    fun takeLane(): Taken =
        when (lanes) {
            is CallerLanes.None -> Taken.Empty
            is CallerLanes.Of -> lanes.pool.tryTake()
        }

    fun returnLane(lane: CallerSend) =
        when (lanes) {
            is CallerLanes.None -> Unit
            is CallerLanes.Of -> lanes.pool.give(lane)
        }

    /** Any thread holding [lane]: hands what it collected to the network. */
    fun sendInPlace(lane: CallerSend): LaneSent =
        when (drive(lane.batch, Who.Caller)) {
            Flushed.Sent -> LaneSent.Sent
            Flushed.Dead -> LaneSent.SocketDied
            Flushed.Full -> {
                callerCounts.deferred()
                port.submit(handOffs[lane.index])
                LaneSent.Deferred
            }
        }

    /** Loop thread: sends [batch]. */
    fun flush(batch: SendBatch): Flushed = drive(batch, Who.Loop)

    /** Loop thread: sends what lanes still hold, oldest lane first, until the socket is full again. */
    fun flushWaiting(): Flushed {
        while (true) {
            val lane = waiting.oldest()
            if (lane !is CallerSend) return Flushed.Sent
            val result = drive(lane.batch, Who.Loop)
            if (result == Flushed.Full) return result
            waiting.removeOldest()
            returnLane(lane)
            if (result == Flushed.Dead) return result
        }
    }

    /** Loop thread, from [AdoptLane]: [lane] holds datagrams to send when the socket can take them. */
    fun adopt(lane: CallerSend) = waiting.add(lane)

    /** Whether lanes wait for a writable socket. */
    fun hasWaiting(): Boolean = !waiting.isEmpty

    /** Loop thread: a receive failed with [error]. */
    fun receiveFailed(error: UdpError): SocketHealth {
        loopCounts.receiveFailed()
        when (val fault = errors.classify(error, probeOf(error))) {
            is SendFault.Socket -> die(fault.fault)
            is SendFault.Full, is SendFault.Datagram -> Unit
        }
        return health.view()
    }

    private fun drive(
        batch: SendBatch,
        who: Who,
    ): Flushed {
        while (batch.pending > 0) {
            val before = batch.pending
            when (val result = io.send(batch)) {
                is SendBatchResult.AllTaken -> {
                    sent(who, before)
                    batch.took(before)
                }
                is SendBatchResult.Partial -> {
                    sent(who, result.taken)
                    batch.took(result.taken)
                    if (who == Who.Loop) loopCounts.refused()
                    when (val fault = errors.classify(result.why, probeOf(result.why))) {
                        is SendFault.Full -> return Flushed.Full
                        is SendFault.Datagram -> batch.took(1)
                        is SendFault.Socket -> {
                            die(fault.fault)
                            batch.took(batch.pending)
                            return Flushed.Dead
                        }
                    }
                }
            }
        }
        return Flushed.Sent
    }

    private fun sent(
        who: Who,
        count: Int,
    ) {
        if (count == 0) return
        errors.taken()
        when (who) {
            Who.Loop -> loopCounts.sent(count)
            Who.Caller -> callerCounts.sent(count)
        }
    }

    private fun probeOf(error: UdpError): SocketProbe = if (error is UdpError.Unnamed) io.probe() else SocketProbe.Open

    private fun die(fault: SocketFault) {
        if (health.died(fault) == Death.First) port.submit(died)
    }
}
