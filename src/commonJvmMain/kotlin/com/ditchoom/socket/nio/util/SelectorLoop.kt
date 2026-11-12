package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TimeoutContext
import com.ditchoom.socket.resumeOffThisThread
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.time.Duration

/**
 * The one owner of a [Selector]: a dedicated platform thread that is the only code to register channels,
 * change interest sets, run `select()` and consume the selected keys. Many sockets share one loop (see
 * [SelectorLoopGroup]), so a connection costs a key, not a thread.
 *
 * A client socket reads and writes one channel, so a read's OP_READ wait and a write's OP_WRITE wait share one
 * [SelectionKey]. Waiters post a command and suspend; the loop ORs each waiter's operation into the key's
 * interest, and on readiness resumes exactly the waiters whose operation is ready and drops exactly those bits.
 *
 * The loop owns the deadlines of the waits it holds: each is a node in a loop-confined ordered set, and the next
 * `select` is bounded by the earliest. Readiness wins a tie with a deadline: a wait whose deadline has passed
 * times out only after one more non-blocking poll found its channel not ready.
 *
 * Only [inbox] crosses threads. The loop never runs user code (see [resumeOffThisThread]); a failure resuming
 * one waiter stays that waiter's, and only a failure of the selector itself ends the loop, with the real cause
 * given to every waiter.
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

        /** Asks how many waiters are parked on [channel], answered from the loop thread. */
        class CountWaiters(
            val channel: SelectableChannel,
            val answer: CompletableDeferred<Int>,
        ) : Command
    }

    /** Where a waiter is parked: nowhere until armed and again once woken, timed out or failed; or on a channel's [Parked] entry. */
    private sealed interface Placement {
        data object Unplaced : Placement
    }

    /** One wait. A wait without a deadline is a different type from one with a deadline, so only the timed ones are in [timers]. */
    private sealed class Waiter(
        val ops: Int,
        val continuation: CancellableContinuation<Unit>,
    ) {
        /** Loop-confined. */
        var placement: Placement = Placement.Unplaced

        class Untimed(
            ops: Int,
            continuation: CancellableContinuation<Unit>,
        ) : Waiter(ops, continuation)

        class Timed(
            ops: Int,
            continuation: CancellableContinuation<Unit>,
            /** [System.nanoTime] at which the wait times out; ordered by subtraction, as nanoTime wraps. */
            val deadline: Long,
            val timeout: Duration,
            val id: Long,
        ) : Waiter(ops, continuation)
    }

    /** Loop-confined: the parked waiters of one channel, and the key that carries their interest. */
    private class Parked(
        val channel: SelectableChannel,
        val key: SelectionKey,
    ) : Placement {
        val waiters = ArrayList<Waiter>(2)

        fun interest() = waiters.fold(0) { acc, waiter -> acc or waiter.ops }
    }

    private sealed interface LoopState {
        data object Running : LoopState

        class Ended(
            val reason: Throwable,
        ) : LoopState
    }

    private val inbox =
        LoopMailbox<Command> {
            try {
                selector.wakeup()
            } catch (_: ClosedSelectorException) {
            }
        }
    private val state = AtomicReference<LoopState>(LoopState.Running)
    private val thread = Thread(this, "socket-nio-loop-${loopCount.incrementAndGet()}").apply { isDaemon = true }
    private val nextId = AtomicInteger()

    private val parked = IdentityHashMap<SelectableChannel, Parked>()
    private val timers =
        TreeSet<Waiter.Timed> { a, b ->
            val byDeadline = a.deadline - b.deadline
            if (byDeadline != 0L) byDeadline.coerceIn(-1L, 1L).toInt() else a.id.compareTo(b.id)
        }

    /** False once the loop has ended; a group stops handing it out. */
    val isRunning: Boolean get() = state.get() === LoopState.Running

    /** Channels with a parked waiter, kept by the loop thread. */
    @Volatile internal var parkedChannelCount: Int = 0
        private set

    /** Keys registered with the selector. */
    internal val registeredKeyCount: Int get() = if (selector.isOpen) selector.keys().size else 0

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
                // Clamped so that now + timeout cannot wrap past the range in which subtraction still orders deadlines.
                val nanos = timeout.inWholeNanoseconds.coerceAtMost(MAX_TIMEOUT_NANOS)
                Waiter.Timed(ops, continuation, System.nanoTime() + nanos, timeout, nextId.incrementAndGet().toLong())
            }
        continuation.invokeOnCancellation { submit(Command.Disarm(waiter)) }
        submit(Command.Arm(channel, waiter))
    }

    /** How many waiters are parked on [channel] right now, as the loop thread counts them; 0 once the loop has ended. */
    internal suspend fun parkedWaiters(channel: SelectableChannel): Int {
        val answer = CompletableDeferred<Int>()
        submit(Command.CountWaiters(channel, answer))
        return answer.await()
    }

    /** Call after [channel] has been closed: its waiters fail instead of waiting on a key that will never fire. */
    fun release(channel: SelectableChannel) = submit(Command.Release(channel))

    private fun submit(command: Command) {
        if (Thread.currentThread() === thread) inbox.postFromLoopThread(command) else inbox.post(command)
        // Checked after posting: a loop that ended in between has made its final drain, so this command is ours to fail.
        when (val current = state.get()) {
            LoopState.Running -> Unit
            is LoopState.Ended -> failInbox(current.reason)
        }
    }

    override fun run() {
        val reason: Throwable =
            try {
                turns()
            } catch (_: ClosedSelectorException) {
                closedFailure()
            } catch (e: Throwable) {
                wrapJvmException(e)
            }
        // Unreachable first, ended second: nobody may be handed a loop that has made its last drain.
        loops.remove(selector, this)
        state.set(LoopState.Ended(reason))
        for (entry in parked.values) entry.waiters.forEach { resumeFailure(it, reason) }
        parked.clear()
        parkedChannelCount = 0
        timers.clear()
        failInbox(reason)
    }

    private fun turns(): Nothing {
        while (true) {
            applyInbox()
            sleepUntilReadyOrDue()
            dispatchSelected()
            if (timersDue()) {
                // Readiness that arrived with the deadline wins: look once more before timing anything out.
                selector.selectNow()
                dispatchSelected()
                expireTimers()
            }
        }
    }

    private fun closedFailure() = SocketClosedException.General("Socket closed while waiting for it to be ready")

    /** Blocks until a channel is ready, a command is posted, or the earliest deadline; never past a pending command or due deadline. */
    private fun sleepUntilReadyOrDue() {
        // A command that raced in after the drain must not be slept through.
        if (!inbox.prepareToSleep()) {
            selector.selectNow()
            return
        }
        if (timers.isEmpty()) {
            selector.select()
            return
        }
        val remaining = timers.first().deadline - System.nanoTime()
        if (remaining <= 0L) selector.selectNow() else selector.select((remaining + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
    }

    private fun dispatchSelected() {
        val selected = selector.selectedKeys().iterator()
        while (selected.hasNext()) {
            val key = selected.next()
            selected.remove()
            dispatch(key)
        }
    }

    private fun timersDue(): Boolean = timers.isNotEmpty() && timers.first().deadline - System.nanoTime() <= 0L

    private fun expireTimers() {
        while (timersDue()) {
            val first = timers.first()
            unpark(first)
            resumeFailure(first, SocketTimeoutException(timeoutContext(first.ops, first.timeout)))
        }
    }

    private fun applyInbox() =
        inbox.drain { command ->
            when (command) {
                is Command.Arm -> arm(command)
                is Command.Disarm -> disarm(command.waiter)
                is Command.Release -> release(command)
                is Command.CountWaiters -> command.answer.complete(parked[command.channel]?.waiters?.size ?: 0)
            }
        }

    /** The loop is over: every queued wait fails with [reason]; a queued disarm or release has nothing left to undo. */
    private fun failInbox(reason: Throwable) =
        inbox.drain { command ->
            when (command) {
                is Command.Arm -> resumeFailure(command.waiter, reason)
                is Command.CountWaiters -> command.answer.complete(0)
                is Command.Disarm, is Command.Release -> Unit
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
                    Parked(command.channel, key).also {
                        parked[command.channel] = it
                        parkedChannelCount = parked.size
                    }
                }
            entry.waiters.add(waiter)
            waiter.placement = entry
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

    private fun disarm(waiter: Waiter) = unpark(waiter)

    /** Takes [waiter] off its channel's key and off the timers; the caller resumes it. A waiter that is not parked is left as it is. */
    private fun unpark(waiter: Waiter) {
        if (waiter is Waiter.Timed) timers.remove(waiter)
        val entry =
            when (val placement = waiter.placement) {
                Placement.Unplaced -> return
                is Parked -> placement
            }
        waiter.placement = Placement.Unplaced
        entry.waiters.remove(waiter)
        if (entry.waiters.isEmpty()) {
            parked.remove(entry.channel)
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
        if (entry != null) failParked(entry, SocketClosedException.General("Socket closed while waiting for it to be ready"))
        // The key of a closed channel is already cancelled; a channel that never had a waiter may still hold one
        // that only the next select would drop.
        command.channel.keyFor(selector)?.cancel()
    }

    private fun failParked(
        entry: Parked,
        failure: Throwable,
    ) {
        for (waiter in entry.waiters) {
            waiter.placement = Placement.Unplaced
            if (waiter is Waiter.Timed) timers.remove(waiter)
            resumeFailure(waiter, failure)
        }
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
            failParked(entry, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        }
    }

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
        private val loopCount = AtomicInteger()

        /**
         * The loop that owns [selector], started on first use; a closed selector has none. A loop whose thread cannot
         * be started is never reachable: the failure is the caller's, mapped like any platform failure.
         */
        fun of(
            selector: Selector,
            startThread: (Thread) -> Unit = { it.start() },
        ): SelectorLoop {
            if (!selector.isOpen) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
            while (true) {
                val existing = loops[selector]
                if (existing != null) {
                    if (existing.isRunning) return existing
                    loops.remove(selector, existing)
                    continue
                }
                val fresh = SelectorLoop(selector)
                if (loops.putIfAbsent(selector, fresh) != null) continue
                try {
                    startThread(fresh.thread)
                } catch (e: Throwable) {
                    // Whoever was already handed it fails with the cause instead of waiting on a loop without a thread.
                    val failure = wrapJvmException(e)
                    loops.remove(selector, fresh)
                    fresh.state.set(LoopState.Ended(failure))
                    fresh.failInbox(failure)
                    throw failure
                }
                return fresh
            }
        }

        /** What a wait on [ops] that outlived [timeout] timed out doing. */
        private fun timeoutContext(
            ops: Int,
            timeout: Duration,
        ): TimeoutContext =
            when {
                ops and SelectionKey.OP_CONNECT != 0 -> TimeoutContext.Connect(timeout, host = null, port = -1)
                ops and SelectionKey.OP_WRITE != 0 -> TimeoutContext.Write(timeout)
                ops and SelectionKey.OP_READ != 0 -> TimeoutContext.Read(timeout)
                else -> TimeoutContext.Platform("Selector wait for interest ops $ops timed out after $timeout")
            }

        /** A new loop on a selector of its own, for [SelectorLoopGroup]. */
        fun open(startThread: (Thread) -> Unit): SelectorLoop = of(Selector.open(), startThread)
    }
}

/**
 * [size] loops, each owned by one daemon platform thread, handed out round-robin. A socket's cost is a
 * registration, not a thread and a selector. The threads start on first use through [startThread] and are
 * daemons, so they cost nothing until used and never keep a process alive. A loop that has ended (its selector
 * failed or was closed by a caller) is replaced the next time its slot is handed out.
 *
 * Every non-blocking NIO socket shares [shared]: `availableProcessors / 2` loops between 1 and
 * [DEFAULT_MAX_LOOPS], because a loop only waits for readiness and resumes continuations, so a handful serves
 * thousands of sockets. `-Dsocket.nio.loops=N` overrides it, clamped to 1..[MAX_LOOPS].
 */
internal class SelectorLoopGroup(
    size: Int,
    private val startThread: (Thread) -> Unit,
) {
    private sealed interface Slot {
        data object Empty : Slot

        class Started(
            val loop: SelectorLoop,
        ) : Slot
    }

    private val slots = AtomicReferenceArray<Slot>(loopCount(size)).also { for (i in 0 until it.length()) it.set(i, Slot.Empty) }
    private val next = AtomicInteger()

    fun next(): SelectorLoop {
        // Masked, not floorMod: Math.floorMod(int, int) arrived in Android API 24.
        val index = (next.getAndIncrement() and Int.MAX_VALUE) % slots.length()
        while (true) {
            val current = slots.get(index)
            if (current is Slot.Started && current.loop.isRunning) return current.loop
            val fresh = SelectorLoop.open(startThread)
            if (slots.compareAndSet(index, current, Slot.Started(fresh))) return fresh
            // Lost the race for the slot: the winner's loop is used, and this one has no sockets yet.
            fresh.selector.close()
        }
    }

    /** The loops started so far, for tests. */
    internal fun running(): List<SelectorLoop> = (0 until slots.length()).mapNotNull { (slots.get(it) as? Slot.Started)?.loop }

    companion object {
        private const val DEFAULT_MAX_LOOPS = 4
        private const val MAX_LOOPS = 16

        internal fun defaultLoopCount(processors: Int): Int = (processors / 2).coerceAtMost(DEFAULT_MAX_LOOPS)

        /** [requested] loops, clamped to the supported range. */
        internal fun loopCount(requested: Int): Int = requested.coerceIn(1, MAX_LOOPS)

        /** The loops every non-blocking NIO socket shares. A group holds no thread until a loop is handed out, so a duplicate construction is discarded for free. */
        val shared: SelectorLoopGroup by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val requested =
                System.getProperty("socket.nio.loops")?.toIntOrNull() ?: defaultLoopCount(Runtime.getRuntime().availableProcessors())
            SelectorLoopGroup(requested) { it.start() }
        }
    }
}
