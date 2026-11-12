package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.resumeOffThisThread
import kotlinx.coroutines.CancellableContinuation
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
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
 * costs no timer thread, no wake-up of one, and no coroutine `withTimeout`. Readiness wins a tie with a
 * deadline: a wait whose deadline has passed is timed out only after one more non-blocking poll found its
 * channel not ready.
 *
 * Nothing but [inbox] (a lock-free queue) crosses threads. The loop never runs user code (see
 * [resumeOffThisThread]), and a failure of one waiter's resume stays that waiter's: only a failure of the
 * selector itself ends the loop, and then every waiter learns the real cause.
 */
internal class SelectorLoop private constructor(
    internal val selector: Selector,
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

    /**
     * One wait. A wait without a deadline is a different class from one with a deadline, so no sentinel
     * stands for "never" and only the timed ones are ever in [timers].
     */
    private sealed class Waiter(
        val ops: Int,
        val continuation: CancellableContinuation<Unit>,
    ) {
        /**
         * Loop-confined: the channel this waiter is parked on, null while it is not armed (not yet, or already woken,
         * timed out or failed). A nullable field rather than a sealed state because arming allocates nothing this way,
         * and a wait is armed on every request/response round trip.
         */
        var parkedOn: SelectableChannel? = null

        class Untimed(
            ops: Int,
            continuation: CancellableContinuation<Unit>,
        ) : Waiter(ops, continuation)

        class Timed(
            ops: Int,
            continuation: CancellableContinuation<Unit>,
            /** [System.nanoTime] at which the wait times out; compared by subtraction, never directly (it wraps). */
            val deadline: Long,
            val timeout: Duration,
            val id: Long,
        ) : Waiter(ops, continuation)
    }

    /** Loop-confined: the parked waiters of one channel, and the key that carries their interest. */
    private class Parked(
        val key: SelectionKey,
    ) {
        val waiters = ArrayList<Waiter>(2)

        fun interest() = waiters.fold(0) { acc, waiter -> acc or waiter.ops }
    }

    private val inbox =
        LoopMailbox<Command> {
            try {
                selector.wakeup()
            } catch (_: ClosedSelectorException) {
            }
        }
    private val ended = AtomicBoolean(false)

    private val thread = Thread(this, "socket-nio-loop-${loopCount.incrementAndGet()}").apply { isDaemon = true }
    private val nextId = AtomicInteger()

    private val parked = IdentityHashMap<SelectableChannel, Parked>()
    private val timers =
        TreeSet<Waiter.Timed> { a, b ->
            val byDeadline = a.deadline - b.deadline
            if (byDeadline != 0L) byDeadline.coerceIn(-1L, 1L).toInt() else a.id.compareTo(b.id)
        }

    /** False once the loop has ended; a group stops handing it out. */
    val isRunning: Boolean get() = !ended.get()

    /** Counts for tests: channels with a parked waiter (kept by the loop thread), and keys registered with the selector. */
    @Volatile internal var parkedChannelCount: Int = 0
        private set
    internal val registeredKeyCount: Int get() = if (selector.isOpen) selector.keys().size else 0

    /** Why the loop ended, for a command that arrives after its last drain. */
    @Volatile private var endReason: Throwable? = null

    /** Suspends until [channel] is ready for any of [ops] on this loop's selector, or [timeout] passes. */
    suspend fun awaitReady(
        channel: SelectableChannel,
        ops: Int,
        timeout: Duration,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val waiter =
            if (timeout.isInfinite()) {
                Waiter.Untimed(ops, continuation)
            } else {
                // Clamped so that now + timeout cannot wrap past the point where subtraction still orders them.
                val nanos = timeout.inWholeNanoseconds.coerceAtMost(MAX_TIMEOUT_NANOS)
                Waiter.Timed(ops, continuation, System.nanoTime() + nanos, timeout, nextId.incrementAndGet().toLong())
            }
        continuation.invokeOnCancellation { submit(Command.Disarm(waiter)) }
        submit(Command.Arm(channel, waiter))
    }

    /** Call after [channel] has been closed: its waiters fail instead of waiting on a key that will never fire. */
    fun release(channel: SelectableChannel) = submit(Command.Release(channel))

    private fun submit(command: Command) {
        inbox.post(command, onLoopThread = Thread.currentThread() === thread)
        // Checked after posting: a loop that ended between the post and here has made its final drain, so
        // nobody else will see this command. (One that ends after this check drains it itself.)
        if (ended.get()) drainInbox(endReason ?: closedFailure())
    }

    override fun run() {
        var failure: Throwable? = null
        try {
            while (true) {
                drainInbox(null)
                // A command that raced in after the drain must not be slept through.
                val wait = if (inbox.prepareToSleep()) millisToNextDeadline() else 0L
                when {
                    wait == 0L -> selector.selectNow()
                    wait < 0L -> selector.select()
                    else -> selector.select(wait)
                }
                dispatchSelected()
                if (timersDue()) {
                    // Readiness that arrived with the deadline wins: look once more before timing anything out.
                    selector.selectNow()
                    dispatchSelected()
                    expireTimers()
                }
            }
        } catch (_: ClosedSelectorException) {
            failure = closedFailure()
        } catch (e: Throwable) {
            // The selector itself failed (select threw, or the loop's own bookkeeping did): the real cause
            // reaches every waiter, not a "closed" that never happened.
            failure = SocketIOException("The selector loop failed: ${e.message}", cause = e)
        } finally {
            // Unreachable first, ended second: nobody may be handed a loop that has made its last drain.
            loops.remove(selector, this)
            ended.set(true)
            val reason = failure ?: closedFailure()
            endReason = reason
            for (entry in parked.values) entry.waiters.forEach { resumeFailure(it, reason) }
            parked.clear()
            parkedChannelCount = 0
            timers.clear()
            drainInbox(reason)
        }
    }

    private fun closedFailure() = SocketClosedException.General("Socket closed while waiting for it to be ready")

    private fun dispatchSelected() {
        val selected = selector.selectedKeys().iterator()
        while (selected.hasNext()) {
            val key = selected.next()
            selected.remove()
            dispatch(key)
        }
    }

    /** Milliseconds to the earliest deadline (at least 1 when one is pending), 0 when one is due, -1 for none. */
    private fun millisToNextDeadline(): Long {
        val first = timers.firstOrNull() ?: return -1L
        val remaining = first.deadline - System.nanoTime()
        return if (remaining <= 0L) 0L else (remaining + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI
    }

    private fun timersDue(): Boolean {
        val first = timers.firstOrNull() ?: return false
        return first.deadline - System.nanoTime() <= 0L
    }

    private fun expireTimers() {
        while (timersDue()) {
            val first = timers.first()
            unpark(first)
            resumeFailure(first, SocketTimeoutException("Selector timed out after waiting ${first.timeout} for interest ops ${first.ops}"))
        }
    }

    /** Applies queued commands; with a [failure] (the loop is over) every Arm fails instead. */
    private fun drainInbox(failure: Throwable?) {
        while (true) {
            when (val command = inbox.poll() ?: return) {
                is Command.Arm -> if (failure != null) resumeFailure(command.waiter, failure) else arm(command)
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
                    Parked(key).also {
                        parked[command.channel] = it
                        parkedChannelCount = parked.size
                    }
                }
            entry.waiters.add(waiter)
            waiter.parkedOn = command.channel
            entry.key.interestOps(entry.interest())
            if (waiter is Waiter.Timed) timers.add(waiter)
        } catch (closed: ClosedChannelException) {
            failArm(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (closed: CancelledKeyException) {
            failArm(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (closed: ClosedSelectorException) {
            failArm(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (misuse: RuntimeException) {
            // E.g. a channel left in blocking mode: the caller's error, and not the loop's to die of.
            failArm(waiter, misuse)
        }
    }

    private fun failArm(
        waiter: Waiter,
        failure: Throwable,
    ) {
        unpark(waiter)
        resumeFailure(waiter, failure)
    }

    private fun disarm(waiter: Waiter) {
        // Not armed (it was already woken, timed out or failed): nothing to undo.
        if (waiter.parkedOn == null) return
        unpark(waiter)
    }

    /** Takes [waiter] off its channel's key and off the timers; the caller resumes it. */
    private fun unpark(waiter: Waiter) {
        if (waiter is Waiter.Timed) timers.remove(waiter)
        val channel = waiter.parkedOn ?: return
        waiter.parkedOn = null
        val entry = parked[channel] ?: return
        entry.waiters.remove(waiter)
        if (entry.waiters.isEmpty()) {
            parked.remove(channel)
            parkedChannelCount = parked.size
        }
        try {
            if (entry.key.isValid) entry.key.interestOps(entry.interest())
        } catch (_: CancelledKeyException) {
        }
    }

    private fun release(command: Command.Release) {
        val entry = parked.remove(command.channel)
        parkedChannelCount = parked.size
        if (entry != null) {
            for (waiter in entry.waiters) {
                waiter.parkedOn = null
                if (waiter is Waiter.Timed) timers.remove(waiter)
                fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready"))
            }
        }
        // The key of a closed channel is already cancelled; cancelling again is a no-op, and a channel that
        // never had a parked waiter may still hold a key that only the next select would drop.
        command.channel.keyFor(selector)?.cancel()
    }

    private fun dispatch(key: SelectionKey) {
        val entry = parked[key.channel()] ?: return // not a key of ours: nothing waits on it
        try {
            val ready = key.readyOps()
            val woken = entry.waiters.filter { it.ops and ready != 0 }
            woken.forEach { unpark(it) }
            woken.forEach { resumeSuccess(it) }
        } catch (closed: CancelledKeyException) {
            parked.remove(key.channel())
            parkedChannelCount = parked.size
            for (waiter in entry.waiters) {
                waiter.parkedOn = null
                if (waiter is Waiter.Timed) timers.remove(waiter)
                fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
            }
        }
    }

    private fun fail(
        waiter: Waiter,
        failure: SocketClosedException,
    ) = resumeFailure(waiter, failure)

    private fun resumeSuccess(waiter: Waiter) = waiter.continuation.resumeOffThisThread(Result.success(Unit))

    private fun resumeFailure(
        waiter: Waiter,
        failure: Throwable,
    ) = waiter.continuation.resumeOffThisThread(Result.failure(failure))

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /** About 146 years: far past any real timeout, and far enough from overflow for nanoTime arithmetic. */
        private const val MAX_TIMEOUT_NANOS = 1L shl 62

        private val loops = ConcurrentHashMap<Selector, SelectorLoop>()

        /** How a loop's thread is started; a test replaces it to make starting fail. */
        @Volatile internal var threadStarter: (Thread) -> Unit = { it.start() }
        private val loopCount = AtomicInteger()

        /**
         * The loop that owns [selector], started on first use; a closed selector has none. A loop whose thread
         * cannot be started is never reachable: the failure is the caller's, typed.
         */
        fun of(selector: Selector): SelectorLoop {
            if (!selector.isOpen) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
            loops[selector]?.let { if (it.isRunning) return it }
            return try {
                loops.compute(selector) { _, existing ->
                    if (existing != null && existing.isRunning) existing else SelectorLoop(selector).also { threadStarter(it.thread) }
                }!!
            } catch (e: Throwable) {
                throw SocketIOException("Could not start the selector loop: ${e.message}", cause = e)
            }
        }

        /** A new loop on a selector of its own, for [SelectorLoopGroup]. */
        fun open(): SelectorLoop = of(Selector.open())
    }
}

/**
 * The loops every non-blocking NIO socket shares: a small fixed set, each owned by one daemon platform
 * thread, handed out round-robin. A socket's cost is a registration, not a thread and a selector. The
 * threads are daemons and start on first use, so a process that never opens a non-blocking socket pays
 * nothing and one that has finished with them is not kept alive by them. A loop that has ended (its
 * selector failed or was closed by a caller) is replaced the next time its slot is handed out.
 *
 * The count is `availableProcessors / 2`, between 1 and [DEFAULT_MAX_LOOPS]: a loop only waits for readiness
 * and resumes continuations, so a handful serves thousands of sockets and more would only add threads.
 * `-Dsocket.nio.loops=N` overrides it, clamped to 1..[MAX_LOOPS].
 */
internal object SelectorLoopGroup {
    private const val DEFAULT_MAX_LOOPS = 4
    private const val MAX_LOOPS = 16

    /** The loop count for [configured] (the property, if it parsed) on a machine with [processors] cores. */
    internal fun loopCount(
        configured: Int?,
        processors: Int,
    ): Int = (configured ?: (processors / 2).coerceAtMost(DEFAULT_MAX_LOOPS)).coerceIn(1, MAX_LOOPS)

    private val slots: AtomicReferenceArray<SelectorLoop?> by lazy {
        AtomicReferenceArray(loopCount(System.getProperty("socket.nio.loops")?.toIntOrNull(), Runtime.getRuntime().availableProcessors()))
    }

    /** The loops started so far, for tests. */
    internal fun running(): List<SelectorLoop> = (0 until slots.length()).mapNotNull { slots.get(it) }

    private val next = AtomicInteger()

    fun next(): SelectorLoop {
        // Masked, not floorMod: Math.floorMod(int, int) arrived in Android API 24.
        val slot = (next.getAndIncrement() and Int.MAX_VALUE) % slots.length()
        while (true) {
            val current = slots.get(slot)
            if (current != null && current.isRunning) return current
            val fresh = SelectorLoop.open()
            if (slots.compareAndSet(slot, current, fresh)) return fresh
            // Lost a race for the slot: the winner's loop is the one to use, and this one has no sockets yet.
            fresh.selector.close()
        }
    }
}
