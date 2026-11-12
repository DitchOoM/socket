package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.IdentityHashMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * The one owner of a [Selector]: a dedicated platform thread that is the only code to register
 * channels, change interest sets, run `select()` and consume the selected keys. Many sockets share one
 * loop (see [SelectorLoopGroup]), so a connection costs a key, not a thread.
 *
 * A non-blocking client socket reads and writes the same channel, so a read's OP_READ wait and a
 * write's OP_WRITE wait share one [SelectionKey]. Waiters therefore only post a command and suspend; the
 * loop ORs each waiter's operation into the key's interest, and on readiness resumes exactly the waiters
 * whose operation is ready and drops exactly those bits.
 *
 * The loop also owns the deadlines of the waits it holds. A wait's timeout is a node in a loop-confined
 * ordered set and the next `select` is bounded by the earliest one, so arming and disarming a deadline
 * costs no timer thread, no wake-up of one, and no coroutine `withTimeout`.
 *
 * Nothing but [inbox] (a lock-free queue) crosses threads. The loop never runs user code: a waiter's
 * continuation is resumed through its own dispatcher, and one with no dispatcher to leave the loop
 * through is moved to [Dispatchers.Default] first (see [resumeAway]).
 */
internal class SelectorLoop private constructor(
    private val selector: Selector,
) : Runnable {
    private sealed interface Command {
        class Arm(
            val channel: SelectableChannel,
            val waiter: Waiter,
        ) : Command

        class Disarm(
            val waiter: Waiter,
        ) : Command

        /** The channel was closed: fail whoever still waits on it and forget it. */
        class Release(
            val channel: SelectableChannel,
        ) : Command
    }

    private class Waiter(
        val ops: Int,
        val continuation: CancellableContinuation<Unit>,
        /** [System.nanoTime] at which the wait times out, or [NO_DEADLINE]. */
        val deadline: Long,
        val timeout: Duration,
        val id: Long,
    ) {
        /** Loop-confined: the channel this waiter is parked on, once armed. */
        var parkedOn: SelectableChannel? = null
    }

    /** Loop-confined: the parked waiters of one channel, and the key that carries their interest. */
    private class Parked(
        val key: SelectionKey,
    ) {
        val waiters = ArrayList<Waiter>(2)

        fun interest() = waiters.fold(0) { acc, waiter -> acc or waiter.ops }
    }

    private val inbox = ConcurrentLinkedQueue<Command>()
    private val ended = AtomicBoolean(false)

    /** True from a submitter's wake-up until the loop next goes to sleep: one wake-up per sleep, however many commands. */
    private val woken = AtomicBoolean(false)

    @Volatile private var thread: Thread? = null
    private val nextId = AtomicInteger()

    private val parked = IdentityHashMap<SelectableChannel, Parked>()
    private val timers = TreeSet<Waiter>(compareBy<Waiter> { it.deadline }.thenBy { it.id })

    /** Suspends until [channel] is ready for any of [ops] on this loop's selector, or [timeout] passes. */
    suspend fun awaitReady(
        channel: SelectableChannel,
        ops: Int,
        timeout: Duration,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val deadline = if (timeout.isInfinite()) NO_DEADLINE else System.nanoTime() + timeout.inWholeNanoseconds
        val waiter = Waiter(ops, continuation, deadline, timeout, nextId.incrementAndGet().toLong())
        continuation.invokeOnCancellation { submit(Command.Disarm(waiter)) }
        submit(Command.Arm(channel, waiter))
    }

    /** Call after [channel] has been closed: its waiters fail instead of waiting on a key that will never fire. */
    fun release(channel: SelectableChannel) = submit(Command.Release(channel))

    private fun submit(command: Command) {
        inbox.add(command)
        if (ended.get()) {
            // The loop has already made its final drain; nobody else will see this command.
            drainInbox(closedFailure())
        } else if (Thread.currentThread() !== thread && woken.compareAndSet(false, true)) {
            try {
                selector.wakeup()
            } catch (_: ClosedSelectorException) {
            }
        }
    }

    override fun run() {
        try {
            while (true) {
                drainInbox(null)
                woken.set(false)
                // A command that raced in after the drain must not be slept through.
                val wait = if (inbox.isEmpty()) millisToNextDeadline() else 0L
                when {
                    wait == 0L -> selector.selectNow()
                    wait < 0L -> selector.select()
                    else -> selector.select(wait)
                }
                val selected = selector.selectedKeys().iterator()
                while (selected.hasNext()) {
                    val key = selected.next()
                    selected.remove()
                    dispatch(key)
                }
                expireTimers()
            }
        } catch (_: ClosedSelectorException) {
            // The selector was closed: the end of this loop.
        } finally {
            ended.set(true)
            loops.remove(selector, this)
            val failure = closedFailure()
            for (entry in parked.values) entry.waiters.forEach { fail(it, failure) }
            parked.clear()
            timers.clear()
            drainInbox(failure)
        }
    }

    private fun closedFailure() = SocketClosedException.General("Socket closed while waiting for it to be ready")

    /** Milliseconds to the earliest deadline (at least 1 when one is pending), 0 when one is due, -1 for none. */
    private fun millisToNextDeadline(): Long {
        val first = timers.firstOrNull() ?: return -1L
        val remaining = first.deadline - System.nanoTime()
        return if (remaining <= 0L) 0L else (remaining + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI
    }

    private fun expireTimers() {
        while (true) {
            val first = timers.firstOrNull() ?: return
            if (first.deadline - System.nanoTime() > 0L) return
            unpark(first)
            resumeFailure(first, SocketTimeoutException("Selector timed out after waiting ${first.timeout} for interest ops ${first.ops}"))
        }
    }

    /** Applies queued commands; with a [failure] (the loop is over) every Arm fails instead. */
    private fun drainInbox(failure: SocketClosedException?) {
        while (true) {
            when (val command = inbox.poll() ?: return) {
                is Command.Arm -> if (failure != null) fail(command.waiter, failure) else arm(command)
                is Command.Disarm -> if (failure == null) disarm(command.waiter)
                is Command.Release -> if (failure == null) release(command)
            }
        }
    }

    private fun arm(command: Command.Arm) {
        val waiter = command.waiter
        if (!waiter.continuation.isActive) return
        try {
            val entry =
                parked[command.channel] ?: run {
                    // keyFor first: register() on a registered channel REPLACES the key's interest set.
                    val key = command.channel.keyFor(selector) ?: command.channel.register(selector, 0)
                    Parked(key).also { parked[command.channel] = it }
                }
            entry.waiters.add(waiter)
            waiter.parkedOn = command.channel
            entry.key.interestOps(entry.interest())
            if (waiter.deadline != NO_DEADLINE) timers.add(waiter)
        } catch (closed: ClosedChannelException) {
            unpark(waiter)
            fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (closed: CancelledKeyException) {
            unpark(waiter)
            fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (closed: ClosedSelectorException) {
            unpark(waiter)
            fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (misuse: RuntimeException) {
            // E.g. a channel left in blocking mode: the caller's error, and not the loop's to die of.
            unpark(waiter)
            resumeFailure(waiter, misuse)
        }
    }

    private fun disarm(waiter: Waiter) {
        // Not armed (it was already woken, timed out or failed): nothing to undo.
        if (waiter.parkedOn == null) return
        unpark(waiter)
    }

    /** Takes [waiter] off its channel's key and off the timers; the caller resumes it. */
    private fun unpark(waiter: Waiter) {
        timers.remove(waiter)
        val channel = waiter.parkedOn ?: return
        waiter.parkedOn = null
        val entry = parked[channel] ?: return
        entry.waiters.remove(waiter)
        if (entry.waiters.isEmpty()) parked.remove(channel)
        try {
            if (entry.key.isValid) entry.key.interestOps(entry.interest())
        } catch (_: CancelledKeyException) {
        }
    }

    private fun release(command: Command.Release) {
        val entry = parked.remove(command.channel) ?: return
        for (waiter in entry.waiters) {
            waiter.parkedOn = null
            timers.remove(waiter)
            fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready"))
        }
        entry.key.cancel()
    }

    private fun dispatch(key: SelectionKey) {
        val entry = parked[key.channel()] ?: return // not a key of ours: nothing waits on it
        try {
            val ready = key.readyOps()
            val woken = entry.waiters.filter { it.ops and ready != 0 }
            woken.forEach { unpark(it) }
            woken.forEach { resumeAway(it.continuation) }
        } catch (closed: CancelledKeyException) {
            parked.remove(key.channel())
            for (waiter in entry.waiters) {
                waiter.parkedOn = null
                timers.remove(waiter)
                fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
            }
        }
    }

    private fun fail(
        waiter: Waiter,
        failure: SocketClosedException,
    ) = resumeFailure(waiter, failure)

    private fun resumeFailure(
        waiter: Waiter,
        failure: Throwable,
    ) {
        val continuation = waiter.continuation
        if (!continuation.isActive) return
        if (leavesTheLoop(continuation)) {
            continuation.resumeWithException(failure)
        } else {
            Dispatchers.Default.dispatch(EmptyCoroutineContext) { continuation.resumeWithException(failure) }
        }
    }

    private fun resumeAway(continuation: CancellableContinuation<Unit>) {
        if (!continuation.isActive) return
        if (leavesTheLoop(continuation)) {
            continuation.resume(Unit)
        } else {
            Dispatchers.Default.dispatch(EmptyCoroutineContext) { continuation.resume(Unit) }
        }
    }

    /**
     * Whether resuming [continuation] hands it to a dispatcher. One with no interceptor, or with
     * [Dispatchers.Unconfined], would run its caller's code right here on the loop thread: the transport's
     * thread must not run user code, so those resume on [Dispatchers.Default] instead.
     */
    private fun leavesTheLoop(continuation: CancellableContinuation<Unit>): Boolean {
        val interceptor = continuation.context[ContinuationInterceptor]
        return interceptor != null && interceptor !== Dispatchers.Unconfined
    }

    companion object {
        private const val NO_DEADLINE = Long.MIN_VALUE
        private const val NANOS_PER_MILLI = 1_000_000L

        private val loops = ConcurrentHashMap<Selector, SelectorLoop>()

        /** The loop that owns [selector], started on first use; a closed selector has none. */
        fun of(selector: Selector): SelectorLoop {
            if (!selector.isOpen) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
            loops[selector]?.let { return it }
            val fresh = SelectorLoop(selector)
            val existing = loops.putIfAbsent(selector, fresh)
            if (existing != null) return existing
            fresh.thread = Thread(fresh, "socket-nio-loop-${loopCount.incrementAndGet()}").apply { isDaemon = true }
            fresh.thread!!.start()
            return fresh
        }

        private val loopCount = AtomicInteger()
    }
}

/**
 * The loops every non-blocking NIO socket shares: a small fixed set of selectors, each owned by one daemon
 * platform thread, handed out round-robin. A socket's cost is a registration, not a thread and a selector.
 * The threads are daemons and start on first use, so a process that never opens a non-blocking socket pays
 * nothing and one that has finished with them is not kept alive by them.
 */
internal object SelectorLoopGroup {
    private val selectors: Array<Selector> by lazy {
        val count = System.getProperty("socket.nio.loops")?.toIntOrNull() ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        Array(count) { Selector.open() }
    }
    private val next = AtomicInteger()

    fun next(): Selector = selectors[Math.floorMod(next.getAndIncrement(), selectors.size)]
}
