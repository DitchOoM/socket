@file:OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class, InternalDatagramLoopApi::class)

package com.ditchoom.socket.udp

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.IdentityHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * An event loop: one platform thread that owns one [Selector] and runs, in turn, the socket readiness
 * of every datagram channel waiting on it, its own timers, and the coroutines dispatched to it.
 *
 * A coroutine running here that receives from a NIO datagram channel parks in this loop's selector
 * ([awaitReadable]) rather than in a `select()` of its own on `Dispatchers.IO`: no thread is parked
 * per channel, and readiness reaches the waiting coroutine without crossing a thread. The timers
 * ([Delay]) fire on the same thread, so a `delay`, `withTimeout` or `select { onTimeout }` in a
 * coroutine dispatched here neither needs kotlinx's shared timer thread nor hops to reach it.
 *
 * What may run here is narrow, and that is the contract: library transport code that never blocks.
 * Application code never does. A coroutine that blocks this thread stalls every connection on the
 * loop, so whatever runs code the application supplied runs on the application's dispatcher instead.
 *
 * ## Life
 *
 * The thread starts when the loop is first held ([hold]) or dispatched to, and exits after
 * [lingerNanos] with nothing to do: no task, no timer, no channel waited on, and no hold. The next
 * hold or dispatch starts a new one. The race between the exit and a dispatch from another thread is
 * settled in [LoopLifecycle]. Threads are daemons, so a loop never keeps a process alive.
 *
 * A thread that cannot be started (native threads or memory exhausted) fails [hold] with
 * [DatagramEventLoopUnavailableException], before anything is opened on the loop. A dispatch that
 * finds no thread and cannot start one cancels the dispatched coroutine's job and runs the queued
 * work on `Dispatchers.IO`, as kotlinx.coroutines does for an executor that rejects a task.
 */
@InternalDatagramLoopApi
class DatagramEventLoop internal constructor(
    private val name: String,
    private val lingerNanos: Long,
    private val threadFactory: ThreadFactory,
) : CoroutineDispatcher(),
    Delay {
    private val lifecycle = LoopLifecycle()

    /** The thread running the loop now; the last one, after it exits. */
    @Volatile
    private var thread: Thread? = null

    /** The running thread's selector; a new thread opens a new one. */
    @Volatile
    private var selector: Selector? = null

    /** Set by the first off-loop dispatch after the loop last looked at its queue: one wakeup per turn. */
    private val wakeupArmed = AtomicBoolean(false)

    // Loop thread only, below.
    private val timers = LoopTimerHeap()
    private var timerSequence = 0L
    private val interests = IdentityHashMap<SelectableChannel, Interest>()
    private var waiters = 0

    /** How many connections hold this loop; a group places the next one on the least held. */
    val holders: Int get() = lifecycle.holders

    /** Whether a thread runs the loop now. */
    val isRunning: Boolean get() = lifecycle.running

    /** The thread running this loop, if one is. For tests and diagnostics. */
    val runningThread: Thread? get() = if (lifecycle.running) thread else null

    /** Whether the caller is this loop's thread. */
    fun isLoopThread(): Boolean = Thread.currentThread() === thread

    /**
     * Holds the loop for one connection, starting its thread when none runs. The thread does not exit
     * while any hold is open; [DatagramEventLoopHold.release] ends this one.
     *
     * @throws DatagramEventLoopUnavailableException when no thread runs the loop and none can be started.
     */
    fun hold(): DatagramEventLoopHold {
        if (lifecycle.acquire() == LoopLifecycle.Next.Start) {
            try {
                startThread()
            } catch (failure: Throwable) {
                lifecycle.abandonHold()
                failStart(failure, EmptyCoroutineContext)
                throw DatagramEventLoopUnavailableException(name, failure)
            }
        }
        return DatagramEventLoopHold(this)
    }

    internal fun releaseHold() {
        lifecycle.release()
        // A loop parked in select() with no deadline is waiting on its holders; let it see this one go.
        wake()
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        when (lifecycle.submit(block, onLoop = isLoopThread())) {
            LoopLifecycle.Next.Nothing -> Unit
            LoopLifecycle.Next.Wake -> wake()
            LoopLifecycle.Next.Start ->
                try {
                    startThread()
                } catch (failure: Throwable) {
                    failStart(failure, context)
                }
        }
    }

    private fun submitFromOutside(task: Runnable) {
        when (lifecycle.submit(task, onLoop = false)) {
            LoopLifecycle.Next.Nothing -> Unit
            LoopLifecycle.Next.Wake -> wake()
            LoopLifecycle.Next.Start ->
                try {
                    startThread()
                } catch (failure: Throwable) {
                    failStart(failure, EmptyCoroutineContext)
                }
        }
    }

    private fun wake() {
        if (wakeupArmed.compareAndSet(false, true)) {
            try {
                selector?.wakeup()
            } catch (_: Exception) {
                // A selector closed by an exiting thread: the thread that replaces it drains the queue.
            }
        }
    }

    private fun startThread() {
        val opened = Selector.open()
        try {
            val started = threadFactory.newThread { run(opened) }
            selector = opened
            thread = started
            started.start()
        } catch (failure: Throwable) {
            runCatching { opened.close() }
            throw failure
        }
    }

    /** No thread could be started: cancel the coroutine that asked, and hand everything queued to IO. */
    private fun failStart(
        failure: Throwable,
        context: CoroutineContext,
    ) {
        context[Job]?.cancel(CancellationException("$name: the event loop thread could not be started", failure))
        for (task in lifecycle.startFailed()) Dispatchers.IO.dispatch(context, task)
    }

    // ---------------------------------------------------------------------------------------------
    // Channel readiness.

    /**
     * Suspends until [channel] is readable. Must be called on this loop's thread, by a coroutine this
     * loop runs. The channel must be non-blocking.
     *
     * @throws ClosedChannelException when the channel is closed (or closing) underneath the wait.
     */
    suspend fun awaitReadable(channel: SelectableChannel) = await(channel, SelectionKey.OP_READ)

    /** As [awaitReadable], for room in [channel]'s send buffer. */
    suspend fun awaitWritable(channel: SelectableChannel) = await(channel, SelectionKey.OP_WRITE)

    private suspend fun await(
        channel: SelectableChannel,
        op: Int,
    ) {
        check(isLoopThread()) { "$name: a channel wait must run on the loop's own thread" }
        suspendCancellableCoroutine { cont ->
            val interest = interestFor(channel)
            interest.add(op, cont)
            waiters++
            if (!interest.refresh()) {
                // The key was cancelled under us: the channel is closed.
                interests.remove(channel)
                waiters -= interest.drop()
                throw ClosedChannelException()
            }
            cont.invokeOnCancellation {
                onLoop {
                    if (interest.remove(op, cont)) {
                        waiters--
                        if (!interest.refresh() || interest.isEmpty) interests.remove(channel)
                    }
                }
            }
        }
    }

    private fun interestFor(channel: SelectableChannel): Interest {
        interests[channel]?.let { return it }
        val sel = checkNotNull(selector) { "$name: no selector" }
        val existing = channel.keyFor(sel)
        val interest =
            if (existing != null) {
                if (!existing.isValid) throw ClosedChannelException()
                (existing.attachment() as? Interest) ?: Interest(existing).also { existing.attach(it) }
            } else {
                val created = Interest(null)
                // Throws ClosedChannelException for a channel already closed.
                created.key = channel.register(sel, 0, created)
                created
            }
        interests[channel] = interest
        return interest
    }

    /**
     * [channel] is being closed: wake everything waiting on it here, so each waiter finds it closed.
     * A closed channel's key is cancelled, and a cancelled key is never selected, so without this a
     * waiter would wait forever. Safe from any thread, before or after the close.
     */
    fun channelClosing(channel: SelectableChannel) {
        onLoop {
            val interest = interests.remove(channel) ?: return@onLoop
            waiters -= interest.resumeAll()
        }
    }

    private fun processSelected(sel: Selector) {
        val keys = sel.selectedKeys()
        val iterator = keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            iterator.remove()
            val interest = key.attachment() as? Interest ?: continue
            val ready =
                try {
                    key.readyOps()
                } catch (_: CancelledKeyException) {
                    SelectionKey.OP_READ or SelectionKey.OP_WRITE
                }
            waiters -= interest.fire(ready)
            if (!interest.refresh() || interest.isEmpty) interests.remove(interest.channel)
        }
    }

    /** The waits on one channel's key. Loop thread only. */
    private class Interest(
        var key: SelectionKey?,
    ) {
        private val readers = ArrayList<CancellableContinuation<Unit>>(1)
        private val writers = ArrayList<CancellableContinuation<Unit>>(1)

        val channel: SelectableChannel get() = key!!.channel()

        val isEmpty: Boolean get() = readers.isEmpty() && writers.isEmpty()

        fun add(
            op: Int,
            cont: CancellableContinuation<Unit>,
        ) {
            if (op == SelectionKey.OP_READ) readers.add(cont) else writers.add(cont)
        }

        fun remove(
            op: Int,
            cont: CancellableContinuation<Unit>,
        ): Boolean = if (op == SelectionKey.OP_READ) readers.remove(cont) else writers.remove(cont)

        /** Sets the key's interest to what is still waited on; false when the key is no longer valid. */
        fun refresh(): Boolean {
            val k = key ?: return false
            val wanted = (if (readers.isEmpty()) 0 else SelectionKey.OP_READ) or (if (writers.isEmpty()) 0 else SelectionKey.OP_WRITE)
            return try {
                if (k.interestOps() != wanted) k.interestOps(wanted)
                true
            } catch (_: CancelledKeyException) {
                false
            }
        }

        /** Resumes the waiters [ready] satisfies; returns how many. */
        fun fire(ready: Int): Int {
            var n = 0
            if (ready and SelectionKey.OP_READ != 0) n += resume(readers)
            if (ready and SelectionKey.OP_WRITE != 0) n += resume(writers)
            return n
        }

        fun resumeAll(): Int = resume(readers) + resume(writers)

        /** Forgets every waiter without resuming it (its registration failed); returns how many. */
        fun drop(): Int {
            val n = readers.size + writers.size
            readers.clear()
            writers.clear()
            return n
        }

        private fun resume(waiting: ArrayList<CancellableContinuation<Unit>>): Int {
            val n = waiting.size
            if (n == 0) return 0
            // Copied first: a resumption dispatches, so nothing re-enters this list now, but keep the
            // iteration independent of it regardless.
            val snapshot = waiting.toTypedArray()
            waiting.clear()
            for (cont in snapshot) cont.resume(Unit)
            return n
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Timers.

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val resumesHere = continuation.context[ContinuationInterceptor] === this
        val timer =
            arm(timeMillis) {
                // Already on this loop: resume in place rather than queue a task to do it.
                if (resumesHere) with(continuation) { resumeUndispatched(Unit) } else continuation.resume(Unit)
            }
        continuation.invokeOnCancellation { timer.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = arm(timeMillis, block)

    private fun arm(
        timeMillis: Long,
        action: Runnable,
    ): DisposableHandle {
        // A wait this long is a wait for cancellation: nothing is armed (Duration.INFINITE arrives as
        // Long.MAX_VALUE), as in kotlinx's own timers.
        if (timeMillis >= NEVER_MILLIS) return NothingArmed
        val deadline = System.nanoTime() + timeMillis.coerceAtLeast(0) * NANOS_PER_MILLI
        val timer = LoopTimer(deadline, action, this)
        onLoop {
            if (!timer.disposed) {
                timer.sequence = timerSequence++
                timers.add(timer)
            }
        }
        return timer
    }

    internal fun disarm(timer: LoopTimer) {
        timer.disposed = true
        if (isLoopThread()) timers.remove(timer) else onLoop { timers.remove(timer) }
    }

    private fun runDueTimers() {
        if (timers.size == 0) return
        val now = System.nanoTime()
        while (true) {
            val next = timers.peek() ?: return
            if (next.deadlineNanos - now > 0) return
            timers.poll()
            if (!next.disposed) runSafely(next.action)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The loop.

    private fun onLoop(block: () -> Unit) {
        if (isLoopThread()) block() else dispatch(EmptyCoroutineContext, Runnable(block))
    }

    private fun runTasks() {
        var budget = MAX_TASKS_PER_TURN
        while (budget-- > 0) {
            val task = lifecycle.tasks.poll() ?: return
            runSafely(task)
        }
    }

    private fun runSafely(task: Runnable) {
        try {
            task.run()
        } catch (failure: Throwable) {
            // A dispatched coroutine handles its own failures; this is a bare task that threw. Report it
            // and keep the loop alive: every other connection on it depends on the next turn.
            reportLoopFailure("a task failed", failure)
        }
    }

    private fun run(sel: Selector) {
        var idleSince = 0L
        try {
            while (true) {
                runDueTimers()
                runTasks()
                // Disarm the wakeup BEFORE the last look at the queue: a dispatch that lands after the
                // look re-arms it and wakes the select below; one that lands before is seen by the look.
                wakeupArmed.set(false)
                if (lifecycle.tasks.isNotEmpty()) {
                    if (sel.selectNow() > 0) processSelected(sel)
                    continue
                }
                val now = System.nanoTime()
                val next = timers.peek()
                if (next != null) {
                    idleSince = 0L
                    val wait = next.deadlineNanos - now
                    val ready = if (wait <= 0) sel.selectNow() else sel.select(ceilMillis(wait))
                    if (ready > 0) processSelected(sel)
                    continue
                }
                if (waiters > 0 || lifecycle.holders > 0) {
                    idleSince = 0L
                    if (sel.select() > 0) processSelected(sel)
                    continue
                }
                // Nothing to do and nothing held: linger, then exit if that is still so.
                if (idleSince == 0L) idleSince = now
                val left = idleSince + lingerNanos - now
                if (left <= 0) {
                    if (lifecycle.tryExit()) return
                    idleSince = 0L
                    continue
                }
                if (sel.select(ceilMillis(left)) > 0) processSelected(sel)
            }
        } catch (failure: Throwable) {
            // Only the selector itself can get here (tasks and timers are caught one by one). Report it,
            // wake every waiter so each re-registers on the thread that replaces this one, and start
            // that thread if anything still needs the loop.
            reportLoopFailure("the selector failed; a new thread takes over", failure)
            for (interest in interests.values.toList()) waiters -= interest.resumeAll()
            interests.clear()
            val stranded = lifecycle.startFailed()
            // Submitted as from another thread: this one is leaving, so it must not count as the loop.
            for (task in stranded) submitFromOutside(task)
            if (timers.size > 0) submitFromOutside(Runnable { })
        } finally {
            runCatching { sel.close() }
        }
    }

    /**
     * Reports a failure the loop survived. Not through the thread's uncaught-exception handler: on Android
     * the default one kills the process, and a loop that outlives a failed task must not take the app down
     * with it. Printed to `System.err` (logcat on Android), with the loop's name and the full stack.
     */
    private fun reportLoopFailure(
        what: String,
        failure: Throwable,
    ) {
        System.err.println("DatagramEventLoop $name: $what (the loop keeps running)")
        failure.printStackTrace()
    }

    override fun toString(): String = name

    private object NothingArmed : DisposableHandle {
        override fun dispose() = Unit
    }

    private companion object {
        /** Tasks run per turn before the selector is polled again, so I/O is never starved by a long queue. */
        const val MAX_TASKS_PER_TURN = 1024
        const val NANOS_PER_MILLI = 1_000_000L
        const val NEVER_MILLIS = Long.MAX_VALUE / 2_000_000L

        /** select() takes whole milliseconds and treats 0 as forever: round up, never down to 0. */
        fun ceilMillis(nanos: Long): Long = ((nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceAtLeast(1)
    }
}

/** One connection's hold on a [DatagramEventLoop]; see [DatagramEventLoop.hold]. */
@InternalDatagramLoopApi
class DatagramEventLoopHold internal constructor(
    val loop: DatagramEventLoop,
) {
    private val released = AtomicBoolean(false)

    /** Ends the hold. Idempotent. */
    fun release() {
        if (released.compareAndSet(false, true)) loop.releaseHold()
    }
}

/**
 * No thread could be started to run a datagram event loop: the process is out of native threads or
 * memory. [cause] is what starting the thread threw.
 */
class DatagramEventLoopUnavailableException(
    loopName: String,
    cause: Throwable,
) : IllegalStateException("the event loop thread $loopName could not be started", cause)
