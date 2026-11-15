@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.DeadlineQueue
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.DatagramVisitor
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What a platform supplies: the loop's one blocking call, and a way to end it from any thread. */
internal interface LoopWait {
    fun await(
        until: NextDue,
        write: WriteInterest,
    ): WakeCause

    /** Any thread. Makes the current or next [await] return. */
    fun wake()

    fun close()
}

internal sealed interface WakeCause {
    object Readable : WakeCause

    object Writable : WakeCause

    object Woken : WakeCause

    object Deadline : WakeCause
}

/** Whether the loop waits for the socket to take more output: only while the network refused a send. */
internal sealed interface WriteInterest {
    object None : WriteInterest

    object Wanted : WriteInterest
}

/** The time, as a parameter, so a pass is a function of its inputs and the time. */
internal fun interface LoopClock {
    fun nowNanos(): Long
}

/** What work running on a loop may reach of it. */
internal interface LoopHost {
    val counts: LoopCounts

    /** Any thread. */
    fun submit(work: LoopWork)
}

/** What a connection needs from the loop that serves it, and from the socket it sends on. */
internal interface ConnHost : LoopHost {
    /** Drained and fired by the loop. */
    val deadlines: DeadlineQueue

    /** Any thread. Arms a deadline for [dueNanos]; the loop is woken only if it sleeps towards a later time. */
    fun armDeadline(
        dueNanos: Long,
        action: Runnable,
    ): DeadlineNode

    /** Any thread. Arms [node], made on [deadlines], with the same wake rule. */
    fun armDeadline(node: DeadlineNode)

    /** A lane for a thread that is not the loop to send from, or [Taken.Empty]: then the loop does the sending. */
    fun takeLane(): Taken

    fun returnLane(lane: CallerSend)

    /**
     * Any thread holding [lane]: hands what it collected to the network. When the network is full the untaken
     * datagrams stay in the lane, which now belongs to the loop.
     */
    fun sendInPlace(lane: CallerSend): LaneSent

    /** Loop thread: the connection is held by the loop in this pass, so its output is to be collected in it. */
    fun entered()
}

/** Work any thread hands the loop. Runs on the loop, once. */
internal abstract class LoopWork : InboxItem() {
    abstract fun run(loop: LoopHost)

    /** The loop had stopped; called once, instead of [run], on the thread that handed the work over or the loop's. */
    abstract fun gone()
}

/** What the loop does with the network's input and where its output comes from. */
internal interface LoopParts {
    /** [buffer] is valid only during the call. */
    fun onDatagram(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    )

    /**
     * Called after input and queued work: serve what they touched and append output to [out] until it is full.
     * Called again in the same pass while it answers [Collected.MoreWaiting] and the network keeps up.
     */
    fun collect(out: SendBatch): Collected

    /** Called first in every pass, before the loop takes datagrams from the socket: work the parts held back from earlier passes. */
    fun passBegan() {}

    /** The loop thread failed with [cause] and stops; end everything on it. Called once, on the loop thread. */
    fun loopFailed(cause: Throwable)
}

internal sealed interface Collected {
    object Everything : Collected

    /** [SendBatch] filled up with output still waiting. */
    object MoreWaiting : Collected
}

internal sealed interface LoopState {
    object Running : LoopState

    class Closing(
        val closed: LoopClosed,
    ) : LoopState
}

/** Told once, on the loop thread, after the socket and the wait are closed. */
internal fun interface LoopClosed {
    fun closed(counts: LoopCounts)
}

/** Counted on the loop. Read on the loop or after [LoopClosed]. */
internal class LoopCounts {
    var passes = 0L
    var waits = 0L
    var datagramsIn = 0L
    var datagramsOut = 0L
    var sendRefusals = 0L
    var receiveFailures = 0L
    var work = 0L
}

/**
 * One thread's run-to-completion loop. A pass: fire due deadlines, take what the socket holds, run queued work,
 * let the parts collect their output, hand it to the network once.
 *
 * Threads that are not the loop may send on the same socket in place, each from a [CallerSend] lane of its own,
 * and may arm deadlines: both are lock-free and wake the loop only when it has to know.
 */
internal class Loop(
    private val wait: LoopWait,
    private val clock: LoopClock,
    private val io: DatagramBatchIo,
    private val parts: LoopParts,
    private val receive: ReceiveBatch,
    private val send: SendBatch,
    private val lanes: CallerLanes = CallerLanes.None,
) : ConnHost,
    LaneHost {
    /** Drained and fired by the loop. Other threads arm through [armDeadline], which also wakes the loop when needed. */
    override val deadlines = DeadlineQueue()
    override val counts = LoopCounts()
    val callerCounts = CallerCounts()

    /** The time the loop sleeps until while [sleep] is [ASLEEP]; [NO_DEADLINE] when only a wake ends the sleep. */
    private val sleepsUntil = AtomicLong(NO_DEADLINE)

    /** Loop thread only: lanes holding datagrams the network refused, oldest first. */
    private var waiting: LaneLink = LaneLink.End
    private var waitingLast: LaneLink = LaneLink.End
    private val inbox = WorkInbox<LoopWork>()
    private val sleep = AtomicInt(AWAKE)
    private var state: LoopState = LoopState.Running
    private val visitor = DatagramVisitor(parts::onDatagram)
    private val runWork: (LoopWork) -> Unit = { it.run(this) }
    private val handOffs: Array<AdoptLane> =
        when (lanes) {
            CallerLanes.None -> emptyArray()
            is CallerLanes.Of -> Array(lanes.pool.size) { AdoptLane(this, lanes.pool[it]) }
        }

    override fun entered() = Unit

    /** Any thread. */
    override fun submit(work: LoopWork) {
        if (inbox.push(work) === Pushed.Refused) return work.gone()
        wakeIfAsleep()
    }

    /**
     * Any thread. Arms a deadline for [dueNanos]. The loop is woken only if it sleeps towards a later time, so the
     * deadline is never late and a deadline later than the loop's next wake costs it nothing.
     */
    override fun armDeadline(
        dueNanos: Long,
        action: Runnable,
    ): DeadlineNode {
        val node = deadlines.arm(dueNanos, action)
        if (dueNanos < sleepsUntil.load()) wakeIfAsleep()
        return node
    }

    /** Any thread. Arms [node], made on [deadlines], with the same wake rule as [armDeadline]. */
    override fun armDeadline(node: DeadlineNode) {
        deadlines.arm(node)
        if (node.dueNanos < sleepsUntil.load()) wakeIfAsleep()
    }

    /** A lane for a thread that is not the loop to send from, or [Taken.Empty]: then the loop does the sending. */
    override fun takeLane(): Taken =
        when (lanes) {
            CallerLanes.None -> Taken.Empty
            is CallerLanes.Of -> lanes.pool.tryTake()
        }

    override fun returnLane(lane: CallerSend) =
        when (lanes) {
            CallerLanes.None -> Unit
            is CallerLanes.Of -> lanes.pool.give(lane)
        }

    /**
     * Any thread holding [lane]: hands what it collected to the network. When the network is full the untaken
     * datagrams stay in the lane, which now belongs to the loop; any other refusal drops that datagram, as the
     * loop's own flush does, and quiche's loss recovery owns what it carried.
     */
    override fun sendInPlace(lane: CallerSend): LaneSent {
        val batch = lane.batch
        while (batch.pending > 0) {
            val before = batch.pending
            when (val result = io.send(batch)) {
                SendBatchResult.AllTaken -> {
                    callerCounts.sent(before)
                    batch.took(before)
                }
                is SendBatchResult.Partial -> {
                    callerCounts.sent(result.taken)
                    if (result.why === UdpError.WouldBlock) {
                        batch.took(result.taken)
                        callerCounts.deferred()
                        submit(handOffs[lane.index])
                        return LaneSent.Deferred
                    }
                    batch.took(result.taken + 1)
                }
            }
        }
        return LaneSent.Sent
    }

    /** Loop thread, from [AdoptLane]: [lane] holds datagrams to send when the socket can take them. */
    override fun adopt(lane: CallerSend) {
        lane.nextLane = LaneLink.End
        when (val last = waitingLast) {
            is CallerSend -> last.nextLane = lane
            LaneLink.End -> waiting = lane
        }
        waitingLast = lane
    }

    private fun wakeIfAsleep() {
        if (sleep.load() == ASLEEP && sleep.compareAndSet(ASLEEP, WOKEN)) {
            callerCounts.woke()
            wait.wake()
        }
    }

    /** Any thread. [closed] is told once the loop has stopped and closed its socket. */
    fun close(closed: LoopClosed) = submit(CloseLoop(this, closed))

    /**
     * Loop thread only, until [close]. The body a platform's loop thread runs. A failure stops the loop and is
     * handed to [LoopParts.loopFailed]; work handed over afterwards is answered with [LoopWork.gone].
     */
    fun run() {
        try {
            while (true) {
                val next = pass(clock.nowNanos())
                when (val now = state) {
                    LoopState.Running -> sleepUntil(next)
                    is LoopState.Closing -> return finish(now.closed)
                }
            }
        } catch (failure: Throwable) {
            // Reported, not rethrown: the parts end every connection on this loop with it as the cause.
            stopAfter(failure)
        }
    }

    /** One pass at [now]. Returns when the loop next needs to run without input. */
    fun pass(now: Long): NextDue {
        counts.passes++
        deadlines.fireDue(now)
        parts.passBegan()
        receiveWaiting()
        counts.work += inbox.drain(runWork)
        flushLanes()
        while (true) {
            val collected = parts.collect(send)
            if (send.pending > 0) flush()
            if (collected === Collected.Everything || send.pending > 0) break
        }
        return deadlines.nextDue(now)
    }

    internal fun beginClosing(closed: LoopClosed) {
        state = LoopState.Closing(closed)
    }

    private fun receiveWaiting() {
        var datagrams = 0
        while (datagrams < MAX_DATAGRAMS_PER_PASS) {
            when (val got = io.receive(receive)) {
                ReceiveBatchResult.Got -> {
                    datagrams += receive.count
                    receive.forEachDatagram(visitor)
                }
                ReceiveBatchResult.Nothing -> break
                is ReceiveBatchResult.Failed -> {
                    counts.receiveFailures++
                    receive.forEachDatagram(visitor)
                    if (got.error === UdpError.SocketClosed) break
                }
            }
        }
        counts.datagramsIn += datagrams
    }

    private fun flush() {
        val before = send.pending
        when (val result = io.send(send)) {
            SendBatchResult.AllTaken -> {
                counts.datagramsOut += before
                send.took(before)
            }
            is SendBatchResult.Partial -> {
                counts.datagramsOut += result.taken
                counts.sendRefusals++
                // Only a full socket is worth waiting for. Any other refusal is that datagram's alone: it is
                // dropped, and quiche's loss recovery owns what it carried.
                send.took(if (result.why === UdpError.WouldBlock) result.taken else result.taken + 1)
            }
        }
    }

    /** Sends what lanes still hold, oldest lane first, until the network is full again. */
    private fun flushLanes() {
        while (true) {
            val lane = waiting
            if (lane !is CallerSend) return
            val before = lane.batch.pending
            when (val result = io.send(lane.batch)) {
                SendBatchResult.AllTaken -> {
                    counts.datagramsOut += before
                    lane.batch.took(before)
                    waiting = lane.nextLane
                    if (waiting === LaneLink.End) waitingLast = LaneLink.End
                    lane.nextLane = LaneLink.End
                    returnLane(lane)
                }
                is SendBatchResult.Partial -> {
                    counts.datagramsOut += result.taken
                    counts.sendRefusals++
                    if (result.why === UdpError.WouldBlock) {
                        lane.batch.took(result.taken)
                        return
                    }
                    lane.batch.took(result.taken + 1)
                }
            }
        }
    }

    private fun sleepUntil(next: NextDue) {
        sleepsUntil.store(
            next.fold(none = { NO_DEADLINE }, due = { it }),
        )
        sleep.store(ASLEEP)
        // A deadline armed, or work pushed, before ASLEEP was published is seen here; one after it sees ASLEEP.
        if (inbox.looksEmpty() && !deadlines.hasInbox()) {
            counts.waits++
            val write =
                when {
                    send.pending > 0 || waiting is CallerSend -> WriteInterest.Wanted
                    else -> WriteInterest.None
                }
            wait.await(next, write)
        }
        sleep.store(AWAKE)
    }

    private fun finish(closed: LoopClosed) {
        io.close()
        wait.close()
        inbox.close(LoopWork::gone)
        closed.closed(counts)
    }

    /** Connections first, so their own close requests are queued before the inbox closes and runs them as gone. */
    private fun stopAfter(failure: Throwable) {
        parts.loopFailed(failure)
        io.close()
        wait.close()
        inbox.close(LoopWork::gone)
    }
}

private class CloseLoop(
    private val loop: Loop,
    private val closed: LoopClosed,
) : LoopWork() {
    override fun run(loop: LoopHost) = this.loop.beginClosing(closed)

    override fun gone() = closed.closed(LoopCounts())
}

private const val AWAKE = 0
private const val ASLEEP = 1
private const val WOKEN = 2
private const val NO_DEADLINE = Long.MAX_VALUE

/** Bounds one pass's receiving so deadlines and queued work are not starved by a flood. */
private const val MAX_DATAGRAMS_PER_PASS = 1024
