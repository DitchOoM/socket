package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.observe.CallerCounts
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.pool.IntStack
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.ReadyTokens
import com.ditchoom.socket.quic.core.sched.HandlerOutbox
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SleepPlan
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.QuicheTime
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread
import kotlinx.coroutines.Runnable

private sealed interface Run {
    data object Running : Run

    class Closing(
        val then: Runnable,
    ) : Run

    /** The stop began: what it releases is never released again, and the loop never runs another pass. */
    data object Stopped : Run
}

/**
 * One platform thread's run-to-completion loop over its sockets. A pass: fire due deadlines, receive from every socket
 * that may hold datagrams, run work handed over, then serve each socket's connection and send its output. Other
 * threads reach it only through [port]. The loop-tier containment site: what [run] catches ends every connection on
 * this loop, and an `Error` is reported on this thread; what one socket's io throws ends that socket's connection only.
 */
@LoopOnly
internal class Loop(
    val id: Int,
    val name: String,
    internal val poller: Poller,
    /** The loop's time, which its connections read too. */
    val clock: LoopClock,
    private val receive: ReceiveBatch,
    /** What the loop frees when it stops: its receive memory. */
    private val freeOnStop: Runnable,
    val time: QuicheTime = QuicheTime.Own,
) {
    val counts = LoopCounts()
    val callerCounts = CallerCounts()
    val port = LoopPort(poller, callerCounts)
    val life = LoopLifeCell()

    /** The lanes of handler pools whose steps a pass resumed, made ready together at the pass's end. */
    val handlers = HandlerOutbox()

    /** Connections placed here, counted when placed and not when they end: what placement balances. */
    val connections = Counter()
    private val slots = ArrayList<LoopSlot>()
    private val free = IntStack()
    private val ready = ReadyTokens(READY_TOKENS)
    private val budget = ReceiveBudget(clock)
    private var run: Run = Run.Running

    /** Any thread: [endpoint] joins this loop; its connection may start once this returns. */
    fun place(endpoint: Endpoint) = port.submit(PlaceWork(this, endpoint))

    /** Any thread: [endpoint]'s socket is reopened on the network that carries its address now (see [Endpoint.rehome]). */
    fun rehome(endpoint: Endpoint) = port.submit(RehomeWork(this, endpoint))

    /** Any thread: a connection its set placed here ended before it joined: the seat it was counted for is given back. */
    fun seatGivenBack() = connections.decrement()

    /** Any thread: the loop stops, ends every connection on it, and then runs [then]. */
    fun close(then: Runnable) = port.submit(StopWork(this, then))

    /** Loop thread only: the body of the loop's thread. A loop that stopped returns at once. */
    fun run() {
        if (run is Run.Stopped) return
        try {
            // A reading after the pass times the wait; one after the wait is the next pass's time.
            var began = clock.now()
            while (true) {
                val next = pass(began)
                val ended = clock.now()
                counts.timedPass(ended.value - began.value)
                when (val now = run) {
                    is Run.Running -> {
                        sleep(next, ended)
                        began = clock.now()
                        counts.timedWait(began.value - ended.value)
                    }
                    is Run.Closing -> {
                        val stopped = stop(ConnEnd.LoopStopped)
                        now.then.run()
                        return contained(stopped)
                    }
                    is Run.Stopped -> return
                }
            }
        } catch (failure: Throwable) {
            // Only a stop that never began runs here: one that began, failed or not, is not run again.
            when (val stopped = stop(ConnEnd.LoopFailed(Thrown(failure)))) {
                is StopOutcome.Failed -> failure.addSuppressed(stopped.first)
                is StopOutcome.Clean, is StopOutcome.AlreadyStopped -> Unit
            }
            contain(failure)
        }
    }

    /**
     * The thread that drives the loop, or any thread before one has: stops it now, with no further pass. What it holds
     * is released once by this stop; a loop that stopped already releases nothing.
     */
    fun stopHere() = contained(stop(ConnEnd.LoopStopped))

    private fun contained(stopped: StopOutcome) =
        when (stopped) {
            is StopOutcome.Failed -> contain(stopped.first)
            is StopOutcome.Clean, is StopOutcome.AlreadyStopped -> Unit
        }

    /** What reached the loop tier: a process-tier failure is reported on this thread, and the rest end with the loop. */
    private fun contain(failure: Throwable) {
        when (Tiers.of(failure)) {
            Tier.Connection -> Unit
            Tier.Process -> reportOnThisThread(failure)
        }
    }

    /** One pass at [now], libquiche's time too where [QuicheTime.Pinned]; returns when the loop next needs to run without input. */
    fun pass(now: Nanos): NextDue =
        when (run) {
            is Run.Stopped -> NextDue.None
            is Run.Running, is Run.Closing ->
                when (val t = time) {
                    is QuicheTime.Own -> traced(TraceSection.LoopPass) { passInside(now) }
                    is QuicheTime.Pinned -> {
                        t.loop.pass(now)
                        try {
                            traced(TraceSection.LoopPass) { passInside(now) }
                        } finally {
                            t.loop.release()
                        }
                    }
                }
        }

    private fun passInside(now: Nanos): NextDue {
        counts.passed()
        handlers.begin()
        port.fireDue(now)
        for (i in 0 until ready.count) {
            when (val slot = slots[ready.tokenAt(i)]) {
                is LoopSlot.Taken -> slot.endpoint.readable(Via.Carrying)
                is LoopSlot.Second -> slot.endpoint.readable(Via.Probing)
                is LoopSlot.Empty -> Unit
            }
        }
        ready.clear()
        // The clock is read only after a socket read that returned datagrams: a pass's reads follow its work.
        budget.begin(now)
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken -> receiveOn(slot.endpoint, Via.Carrying)
                is LoopSlot.Second -> receiveOn(slot.endpoint, Via.Probing)
                is LoopSlot.Empty -> Unit
            }
        }
        counts.ran(port.runWork())
        var backlog = false
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken -> {
                    when (slot.endpoint.serve(poller, this, now, port)) {
                        Stay.Left -> vacate(slot.endpoint)
                        Stay.Stays -> if (slot.endpoint.hasBacklog()) backlog = true
                    }
                }
                is LoopSlot.Second, is LoopSlot.Empty -> Unit
            }
        }
        handlers.end()
        val due = port.nextDue(now)
        // Datagrams the io holds are invisible to the poller: with any, the next pass is due now (a poll of zero), whatever
        // cut this one short (the per-pass bound included).
        return if (backlog) NextDue.at(now.value) else due
    }

    internal fun attach(endpoint: Endpoint) {
        val token = free.popOr { slots.size.also { slots.add(LoopSlot.Empty) } }
        slots[token] = LoopSlot.Taken(endpoint)
        endpoint.placed(token, poller, this)
        poller.add(endpoint.refs.carrying, token)
    }

    /** A slot of [endpoint]'s second socket, which the poller reports as its [Via.Probing]; its token. */
    internal fun seat(endpoint: Endpoint): Int {
        val token = free.popOr { slots.size.also { slots.add(LoopSlot.Empty) } }
        slots[token] = LoopSlot.Second(endpoint)
        return token
    }

    internal fun unseat(token: Int) {
        slots[token] = LoopSlot.Empty
        free.push(token)
    }

    internal fun beginClosing(then: Runnable) {
        when (run) {
            is Run.Running -> run = Run.Closing(then)
            // A second close in the same pass: the port answers it as gone once the loop stopped, running [then].
            is Run.Closing, is Run.Stopped -> port.submit(StopWork(this, then))
        }
    }

    private fun receiveOn(
        endpoint: Endpoint,
        via: Via,
    ) {
        try {
            endpoint.receive(via, receive, counts, clock, budget, poller, this)
        } catch (failure: Exception) {
            receive.release()
            endpoint.ioThrew(Thrown(failure), poller, this)
        }
    }

    private fun vacate(endpoint: Endpoint) {
        slots[endpoint.token] = LoopSlot.Empty
        free.push(endpoint.token)
        connections.decrement()
    }

    private fun sleep(
        next: NextDue,
        now: Nanos,
    ) {
        when (port.beginSleep(next)) {
            SleepPlan.Await -> {
                counts.waited()
                traced(TraceSection.PollWait) { poller.await(next, ready, now) }
            }
            SleepPlan.Skip -> Unit
        }
        port.endSleep()
    }

    /**
     * Connections first, so their own requests are queued before the port shuts and answers them as gone. Runs once:
     * it moves to [Run.Stopped] before it releases anything, and takes each endpoint off its slot before ending it, so
     * a step that throws is not repeated and the steps after it still run.
     */
    private fun stop(end: ConnEnd): StopOutcome {
        if (run is Run.Stopped) return StopOutcome.AlreadyStopped
        run = Run.Stopped
        life.stopped()
        // A pass that threw left its resumed lanes unclaimed; what the stop resumes is queued as from any thread.
        var outcome: StopOutcome = StopOutcome.Clean.then { handlers.end() }
        for (i in slots.indices) {
            val slot = slots[i]
            slots[i] = LoopSlot.Empty
            when (slot) {
                is LoopSlot.Taken -> outcome = outcome.then { slot.endpoint.loopEnded(end, poller, this) }
                is LoopSlot.Second, is LoopSlot.Empty -> Unit
            }
        }
        slots.clear()
        outcome = outcome.then { poller.close() }
        outcome = outcome.then { port.stop(end) }
        outcome = outcome.then { receive.disarm() }
        return outcome.then { freeOnStop.run() }
    }

    private companion object {
        const val READY_TOKENS = 256
    }
}
