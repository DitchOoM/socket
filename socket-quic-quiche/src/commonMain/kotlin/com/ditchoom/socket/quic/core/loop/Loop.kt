package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
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
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.Round
import com.ditchoom.socket.quic.currentThreadId
import com.ditchoom.socket.quic.core.sched.SleepPlan
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread
import kotlinx.coroutines.Runnable

/** Runs [step]; what it throws is kept in the outcome instead of cutting the stop short. */
private inline fun StopOutcome.then(step: () -> Unit): StopOutcome =
    try {
        step()
        this
    } catch (failure: Throwable) {
        when (this) {
            is StopOutcome.Failed -> {
                first.addSuppressed(failure)
                this
            }
            is StopOutcome.Clean, is StopOutcome.AlreadyStopped -> StopOutcome.Failed(failure)
        }
    }

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
    private val poller: Poller,
    private val clock: LoopClock,
    private val receive: ReceiveBatch,
    /** What the loop frees when it stops: its receive memory. */
    private val freeOnStop: Runnable,
) {
    val counts = LoopCounts()
    val callerCounts = CallerCounts()
    val port = LoopPort(poller, callerCounts)
    val life = LoopLifeCell()

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

    /** Any thread: [endpoint]'s socket is moved to [bind]'s address, a new local endpoint (see [Endpoint.move]). */
    fun move(
        endpoint: Endpoint,
        bind: PathKey,
        prior: RttPrior,
    ) = port.submit(MoveWork(this, endpoint, bind, prior))

    /** Any thread: a connection its set placed here ended before it joined: the seat it was counted for is given back. */
    fun seatGivenBack() = connections.decrement()

    /** Any thread: the loop stops, ends every connection on it, and then runs [then]. */
    fun close(then: Runnable) = port.submit(StopWork(this, then))

    /** Loop thread only: the body of the loop's thread. A loop that stopped returns at once. */
    fun run() {
        if (run is Run.Stopped) return
        try {
            // Two clock reads per iteration: one after the pass and one after the wait, which is the next pass's time.
            var began = clock.now()
            while (true) {
                val next = pass(began)
                val ended = clock.now()
                counts.timedPass(ended.value - began.value)
                when (val now = run) {
                    is Run.Running -> {
                        sleep(next)
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

    /** One pass at [now]. Returns when the loop next needs to run without input; a stopped loop needs nothing. */
    fun pass(now: Nanos): NextDue =
        when (run) {
            is Run.Stopped -> NextDue.None
            is Run.Running, is Run.Closing -> traced(TraceSection.LoopPass) { passInside(now) }
        }

    private fun passInside(now: Nanos): NextDue {
        counts.passed()
        port.handlers.passBegins(currentThreadId())
        port.fireDue(now)
        for (i in 0 until ready.count) {
            when (val slot = slots[ready.tokenAt(i)]) {
                is LoopSlot.Taken -> slot.endpoint.readable()
                is LoopSlot.Empty -> Unit
            }
        }
        ready.clear()
        // The clock is read again only after a socket gave datagrams: a pass's reads follow its work, not its connections.
        var at = now
        budget.begin(at)
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken -> {
                    val before = counts.datagramsIn
                    receiveOn(slot.endpoint, at)
                    if (counts.datagramsIn != before) at = clock.now()
                }
                is LoopSlot.Empty -> Unit
            }
        }
        counts.ran(port.runWork())
        var backlog = serveAll(now)
        // SPIKE: the handler phase. Continuations resumed during receive and serve run here, never inside quiche's
        // processing; what they hand the connections is written by the serve that follows, with any ACK held for them.
        val handlers = port.handlers
        while (handlers.nextRound() == Round.Due) {
            handlers.runReady(clock)
            counts.ran(port.runWork())
            if (serveAll(now)) backlog = true
        }
        if (handlers.hasReady()) backlog = true
        val due = port.nextDue(now)
        // Datagrams the io holds are invisible to the poller: with any, the next pass is due now (a poll of zero), whatever
        // cut this one short (the per-pass bound included).
        return if (backlog) NextDue.at(now.value) else due
    }

    /** Serves every socket's connections; returns whether one holds datagrams the poller will not signal. */
    private fun serveAll(now: Nanos): Boolean {
        port.handlers.serving()
        var backlog = false
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken -> {
                    when (slot.endpoint.serve(poller, now, port)) {
                        Stay.Left -> vacate(slot.endpoint)
                        Stay.Stays -> if (slot.endpoint.hasBacklog()) backlog = true
                    }
                }
                is LoopSlot.Empty -> Unit
            }
        }
        return backlog
    }

    internal fun attach(endpoint: Endpoint) {
        val token = free.popOr { slots.size.also { slots.add(LoopSlot.Empty) } }
        slots[token] = LoopSlot.Taken(endpoint)
        endpoint.placed(token)
        poller.add(endpoint.socketRef, token)
    }

    internal fun reopen(endpoint: Endpoint) = endpoint.rehome(poller)

    internal fun moveTo(
        endpoint: Endpoint,
        bind: PathKey,
        prior: RttPrior,
    ) = endpoint.move(poller, bind, prior)

    internal fun beginClosing(then: Runnable) {
        when (run) {
            is Run.Running -> run = Run.Closing(then)
            // A second close in the same pass: the port answers it as gone once the loop stopped, running [then].
            is Run.Closing, is Run.Stopped -> port.submit(StopWork(this, then))
        }
    }

    private fun receiveOn(
        endpoint: Endpoint,
        at: Nanos,
    ) {
        try {
            endpoint.receive(receive, counts, at, budget)
        } catch (failure: Exception) {
            receive.release()
            endpoint.ioThrew(Thrown(failure))
        }
    }

    private fun vacate(endpoint: Endpoint) {
        slots[endpoint.token] = LoopSlot.Empty
        free.push(endpoint.token)
        connections.decrement()
    }

    private fun sleep(next: NextDue) {
        when (port.beginSleep(next)) {
            SleepPlan.Await -> {
                counts.waited()
                traced(TraceSection.PollWait) { poller.await(next, ready) }
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
        var outcome: StopOutcome = StopOutcome.Clean
        for (i in slots.indices) {
            val slot = slots[i]
            slots[i] = LoopSlot.Empty
            when (slot) {
                is LoopSlot.Taken -> outcome = outcome.then { slot.endpoint.loopEnded(end, poller) }
                is LoopSlot.Empty -> Unit
            }
        }
        slots.clear()
        outcome = outcome.then { poller.close() }
        outcome = outcome.then { port.stop(end) }
        outcome = outcome.then { port.handlers.stop() }
        outcome = outcome.then { receive.disarm() }
        return outcome.then { freeOnStop.run() }
    }

    private companion object {
        const val READY_TOKENS = 256
    }
}
