@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.DeadlineQueue
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.DatagramVisitor
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlin.concurrent.atomics.AtomicInt
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

/** Work any thread hands the loop. Runs on the loop, once. */
internal abstract class LoopWork : InboxItem() {
    abstract fun run(loop: Loop)

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
 */
internal class Loop(
    private val wait: LoopWait,
    private val clock: LoopClock,
    private val io: DatagramBatchIo,
    private val parts: LoopParts,
    private val receive: ReceiveBatch,
    private val send: SendBatch,
) {
    val deadlines = DeadlineQueue()
    val counts = LoopCounts()
    private val inbox = WorkInbox<LoopWork>()
    private val sleep = AtomicInt(AWAKE)
    private var state: LoopState = LoopState.Running
    private val visitor = DatagramVisitor(parts::onDatagram)
    private val runWork: (LoopWork) -> Unit = { it.run(this) }

    /** Any thread. */
    fun submit(work: LoopWork) {
        if (inbox.push(work) === Pushed.Refused) return work.gone()
        if (sleep.load() == ASLEEP && sleep.compareAndSet(ASLEEP, WOKEN)) wait.wake()
    }

    /** Any thread. [closed] is told once the loop has stopped and closed its socket. */
    fun close(closed: LoopClosed) = submit(CloseLoop(closed))

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
        receiveWaiting()
        counts.work += inbox.drain(runWork)
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

    private fun sleepUntil(next: NextDue) {
        sleep.store(ASLEEP)
        if (inbox.looksEmpty()) {
            counts.waits++
            wait.await(next, if (send.pending > 0) WriteInterest.Wanted else WriteInterest.None)
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
    private val closed: LoopClosed,
) : LoopWork() {
    override fun run(loop: Loop) = loop.beginClosing(closed)

    override fun gone() = closed.closed(LoopCounts())
}

private const val AWAKE = 0
private const val ASLEEP = 1
private const val WOKEN = 2

/** Bounds one pass's receiving so deadlines and queued work are not starved by a flood. */
private const val MAX_DATAGRAMS_PER_PASS = 1024
