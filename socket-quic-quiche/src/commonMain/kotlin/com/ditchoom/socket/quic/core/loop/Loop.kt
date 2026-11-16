@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.observe.CallerCounts
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.pool.IntStack
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.ReadyTokens
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.sched.SleepPlan
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a loop runs, as any thread may read it. */
internal enum class LoopLife { Alive, Stopped }

/** A loop's life, written once by the loop when it stops. */
@Published
internal class LoopLifeCell {
    private val state = AtomicReference(LoopLife.Alive)

    fun load(): LoopLife = state.load()

    fun stopped() = state.store(LoopLife.Stopped)
}

private sealed interface Run {
    data object Running : Run

    class Closing(
        val then: Runnable,
    ) : Run
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
    private var run: Run = Run.Running

    /** Any thread: [endpoint] joins this loop; its connection may start once this returns. */
    fun place(endpoint: Endpoint) = port.submit(PlaceWork(this, endpoint))

    /** Any thread: [endpoint]'s socket is reopened on the network that carries its address now (see [Endpoint.rehome]). */
    fun rehome(endpoint: Endpoint) = port.submit(RehomeWork(this, endpoint))

    /** Any thread: the loop stops, ends every connection on it, and then runs [then]. */
    fun close(then: Runnable) = port.submit(StopWork(this, then))

    /** Loop thread only, until stopped: the body of the loop's thread. */
    fun run() {
        try {
            while (true) {
                val next = pass(clock.now())
                when (val now = run) {
                    is Run.Running -> sleep(next)
                    is Run.Closing -> {
                        stop(ConnEnd.LoopStopped)
                        return now.then.run()
                    }
                }
            }
        } catch (failure: Throwable) {
            stop(ConnEnd.LoopFailed(Thrown(failure)))
            when (Tiers.of(failure)) {
                Tier.Connection -> Unit
                Tier.Process -> reportOnThisThread(failure)
            }
        }
    }

    /** One pass at [now]. Returns when the loop next needs to run without input. */
    fun pass(now: Nanos): NextDue = traced(TraceSection.LoopPass) { passInside(now) }

    private fun passInside(now: Nanos): NextDue {
        counts.passed()
        port.fireDue(now)
        for (i in 0 until ready.count) {
            when (val slot = slots[ready.tokenAt(i)]) {
                is LoopSlot.Taken -> slot.endpoint.readable()
                is LoopSlot.Empty -> Unit
            }
        }
        ready.clear()
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken -> receiveOn(slot.endpoint)
                is LoopSlot.Empty -> Unit
            }
        }
        counts.ran(port.runWork())
        var backlog = false
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken ->
                    when (slot.endpoint.serve(poller, now, port)) {
                        Stay.Left -> vacate(slot.endpoint)
                        Stay.Stays -> if (slot.endpoint.hasBacklog()) backlog = true
                    }
                is LoopSlot.Empty -> Unit
            }
        }
        val due = port.nextDue(now)
        // Datagrams the io holds are invisible to the poller: with any, the next pass is due now (a poll of zero), whatever
        // cut this one short (the per-pass bound included).
        return if (backlog) NextDue.at(now.value) else due
    }

    internal fun attach(endpoint: Endpoint) {
        val token = free.popOr { slots.size.also { slots.add(LoopSlot.Empty) } }
        slots[token] = LoopSlot.Taken(endpoint)
        endpoint.placed(token)
        poller.add(endpoint.socketRef, token)
    }

    internal fun reopen(endpoint: Endpoint) = endpoint.rehome(poller)

    internal fun beginClosing(then: Runnable) {
        run = Run.Closing(then)
    }

    private fun receiveOn(endpoint: Endpoint) {
        try {
            endpoint.receive(receive, counts, clock.now())
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

    /** Connections first, so their own requests are queued before the port shuts and answers them as gone. */
    private fun stop(end: ConnEnd) {
        life.stopped()
        for (i in slots.indices) {
            when (val slot = slots[i]) {
                is LoopSlot.Taken -> slot.endpoint.loopEnded(end, poller)
                is LoopSlot.Empty -> Unit
            }
        }
        slots.clear()
        poller.close()
        port.stop(end)
        freeOnStop.run()
    }

    private companion object {
        const val READY_TOKENS = 256
    }
}

/** Wakes the loop when a socket died on another thread: the next pass sees the socket's health. One per socket. */
internal class SocketDiedNotice : LoopWork() {
    override fun run(port: LoopPort) = Unit

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: a new connection's socket joins the loop. */
private class PlaceWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.attach(endpoint)

    override fun gone(end: ConnEnd) = endpoint.unplaced(end)
}

/** Loop work: a socket is reopened on a changed network. */
private class RehomeWork(
    private val loop: Loop,
    private val endpoint: Endpoint,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.reopen(endpoint)

    override fun gone(end: ConnEnd) = Unit
}

/** Loop work: the loop's owner stops it. */
private class StopWork(
    private val loop: Loop,
    private val then: Runnable,
) : LoopWork() {
    override fun run(port: LoopPort) = loop.beginClosing(then)

    override fun gone(end: ConnEnd) = then.run()
}
