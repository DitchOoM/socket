@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.Entry
import com.ditchoom.socket.quic.core.sched.LaneSent
import com.ditchoom.socket.quic.core.sched.LaneTake
import com.ditchoom.socket.quic.core.sched.Leave
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.sched.OwnerWord
import com.ditchoom.socket.quic.core.sched.Pushed
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.Shut
import com.ditchoom.socket.quic.core.sched.WorkLeft
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether the loop is inside this connection in the current pass. */
internal enum class LoopHold { Out, In }

/**
 * One QUIC connection on one loop: who enters it, and leaving. Whoever holds its [OwnerWord] is inside and the only
 * thread touching its quiche handle: the loop, when a datagram, a deadline or work touches it, or a caller allowed to
 * enter ([CallerPlace.MayEnter]) that finds it free. Whoever is inside serves it through [Turn], collects its output
 * and leaves. Any thread hands it work through [submit]. [hold] is the loop's; the flags are any thread's.
 */
@LoopOnly
internal class Conn(
    private val turn: Turn,
    private val port: LoopPort,
    private val socket: SendPort,
    private val clock: LoopClock,
    private val loopEntrant: Entrant,
    val counts: ConnCounts,
) {
    private val owner = OwnerWord()
    private val callerEntrant = Entrant.caller(CALLER_TOKEN)
    private val serveQueued = AtomicInt(IDLE)
    private val timerQueued = AtomicInt(IDLE)
    private val serve = ServeConn(this)
    private val timerWork = TimerWork(this)
    private val onTimer = Runnable { timerFired() }
    private var hold = LoopHold.Out

    /** Any thread: whether the connection has ended, and how. */
    fun life(): Life = turn.life()

    /** Any thread. Puts the connection in the loop's next pass: a new client's first flight goes out there. */
    fun start() = scheduleServe()

    /** Any thread. [work] runs inside: on the thread inside, or else here if [place] allows it and on the loop if not. */
    fun submit(
        work: ConnWork,
        place: CallerPlace = CallerPlace.HandToLoop,
    ) {
        when (val pushed = turn.push(work)) {
            is Shut -> return work.gone(pushed.end)
            is Pushed.Open -> Unit
        }
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> counts.leftForHolder()
            WorkLeft.NobodyInside ->
                when (place) {
                    CallerPlace.MayEnter -> enterInPlace()
                    CallerPlace.HandToLoop -> handToLoop()
                }
        }
    }

    /** Loop thread: [work] runs inside in this pass's serve, unless a caller is inside, who runs it. */
    fun submitFromLoop(work: ConnWork) {
        submit(work)
        touch()
    }

    /**
     * Loop thread: a datagram for this connection, at the start of [bytes], read in the receive phase that began [at];
     * copied when a caller is inside. Every datagram of one phase shares [at], so a recording keeps them one receive
     * pass: quiche has all of them before the connection sends.
     */
    fun received(
        bytes: NativeBytes,
        length: Int,
        at: Nanos,
    ) {
        when (touch()) {
            LoopHold.In -> turn.receive(bytes, length, at)
            LoopHold.Out -> copyForHolder(bytes, length)
        }
    }

    /** Loop thread, once or more per pass: serves the connection if the loop is inside, and leaves when done. */
    fun turn(out: SendBatch): Collected {
        if (hold == LoopHold.Out) return Collected.Everything
        if (turn.serve(clock.now(), out, onTimer) == Collected.MoreWaiting) return Collected.MoreWaiting
        return when (owner.tryLeave(loopEntrant)) {
            Leave.Left -> {
                hold = LoopHold.Out
                freeWhenNobodyInside()
                Collected.Everything
            }
            Leave.WorkArrived -> Collected.MoreWaiting
        }
    }

    /**
     * Loop thread, or a thread the stopped loop answered: the loop stopped or failed, or the socket died, and the
     * connection ends with [end] inside: here when it is free or the loop holds it, else on the caller inside.
     */
    fun endWithLoop(end: ConnEnd) {
        when (turn.push(EndWork(end))) {
            is Shut -> return
            is Pushed.Open -> Unit
        }
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> if (hold == LoopHold.In) finishAsLoop()
            WorkLeft.NobodyInside ->
                when (owner.tryEnter(loopEntrant)) {
                    Entry.Entered -> {
                        hold = LoopHold.In
                        finishAsLoop()
                    }
                    Entry.Busy -> Unit
                }
        }
    }

    /** Inside, as the loop, with nothing left to send: runs what is queued and leaves. */
    private fun finishAsLoop() {
        do {
            turn.drain()
        } while (owner.tryLeave(loopEntrant) == Leave.WorkArrived)
        hold = LoopHold.Out
        freeWhenNobodyInside()
    }

    /** Any thread: frees quiche's memory once nobody is inside, then runs [then] on the thread that freed. */
    fun release(then: Runnable) {
        turn.requestRelease(then)
        freeWhenNobodyInside()
    }

    internal fun touchedByLoop() {
        serveQueued.store(IDLE)
        touch()
    }

    internal fun timerFiredInside() {
        timerQueued.store(IDLE)
        turn.expire()
    }

    private fun touch(): LoopHold {
        if (hold == LoopHold.In) return hold
        hold =
            when (owner.tryEnter(loopEntrant)) {
                Entry.Entered -> LoopHold.In
                Entry.Busy -> LoopHold.Out
            }
        return hold
    }

    private fun copyForHolder(
        bytes: NativeBytes,
        length: Int,
    ) {
        val work =
            when (val copy = turn.copy(bytes, length)) {
                is CopiedWork -> copy
                is Copy.Dropped -> return
            }
        when (val pushed = turn.push(work)) {
            is Shut -> {
                counts.datagramDropped()
                return work.gone(pushed.end)
            }
            is Pushed.Open -> Unit
        }
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> Unit
            WorkLeft.NobodyInside -> scheduleServe()
        }
    }

    /** A caller, with the connection free, takes a lane and the connection, serves it, sends in place and leaves. */
    private fun enterInPlace() {
        val lane =
            when (val taken = socket.takeLane()) {
                is CallerSend -> taken
                is LaneTake.None -> return handToLoop()
            }
        when (owner.tryEnter(callerEntrant)) {
            Entry.Busy -> {
                counts.leftForHolder()
                socket.returnLane(lane)
            }
            Entry.Entered -> {
                counts.enteredInPlace()
                serveAsCaller(lane)
            }
        }
    }

    private fun serveAsCaller(first: CallerSend) {
        var lane: LaneTake = first
        while (true) {
            val now = clock.now()
            val collected =
                when (val held = lane) {
                    is CallerSend -> {
                        val served = turn.serveAsCaller(now, held.batch, onTimer)
                        if (held.batch.pending > 0 && socket.sendInPlace(held) == LaneSent.Deferred) lane = LaneTake.None
                        served
                    }
                    is LaneTake.None -> turn.drainAsCaller(now)
                }
            if (collected == Collected.MoreWaiting) continue
            if (owner.tryLeave(callerEntrant) == Leave.Left) break
        }
        freeWhenNobodyInside()
        when (val last = lane) {
            is CallerSend -> {
                // A failure can leave datagrams collected for a connection that has ended: they are not sent.
                last.batch.took(last.batch.pending)
                socket.returnLane(last)
            }
            is LaneTake.None -> handToLoop()
        }
    }

    private fun handToLoop() {
        counts.handedToLoop()
        scheduleServe()
    }

    /** Whoever leaves calls this: with a release asked for and nobody inside, it enters for good and frees. */
    private fun freeWhenNobodyInside() {
        if (turn.releaseAsked() == ReleaseAsk.No) return
        when (owner.tryEnter(loopEntrant)) {
            Entry.Busy -> Unit
            Entry.Entered -> turn.free(clock.now())
        }
    }

    private fun scheduleServe() {
        if (serveQueued.compareAndSet(IDLE, QUEUED)) port.submit(serve)
    }

    /** Loop thread: the connection's deadline fell due; quiche's timeout runs inside, with the other work. */
    private fun timerFired() {
        if (timerQueued.compareAndSet(IDLE, QUEUED)) submit(timerWork)
    }
}

/** Puts a connection in the loop's next pass. One per connection, queued at most once at a time. */
private class ServeConn(
    private val conn: Conn,
) : LoopWork() {
    override fun run(port: LoopPort) = conn.touchedByLoop()

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the connection's deadline fell due. One per connection. */
private class TimerWork(
    private val conn: Conn,
) : ConnWork() {
    override fun run(inside: Inside) = conn.timerFiredInside()

    override fun gone(end: ConnEnd) = Unit
}

/** Inside, once: the loop or the socket ends the connection with [end]. */
private class EndWork(
    private val end: ConnEnd,
) : ConnWork() {
    override fun run(inside: Inside) = inside.end(end)

    override fun gone(end: ConnEnd) = Unit
}

private const val IDLE = 0
private const val QUEUED = 1
private const val CALLER_TOKEN = 1L
