package com.ditchoom.socket.nio.util

import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one owner of a [Selector]: a dedicated platform thread that is the only code to register
 * channels, change interest sets, run `select()` and consume the selected keys.
 *
 * A non-blocking client socket reads and writes the same channel, so a read's OP_READ wait and a
 * write's OP_WRITE wait share one [SelectionKey]. Letting each waiter register and poll for itself
 * made the write's `register(selector, OP_WRITE)` replace the read's interest and let the two race
 * to remove the key from the selected set. Here waiters only post a command and suspend; the loop
 * thread ORs each waiter's operation into the key's interest, and on readiness resumes exactly the
 * waiters whose operation is ready and clears exactly those bits.
 *
 * Nothing but [inbox] (a lock-free queue) crosses threads; [waiters] is touched only by the loop
 * thread. The loop never runs `select()` on a caller's thread and a caller never blocks.
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
    }

    private class Waiter(
        val ops: Int,
        val continuation: CancellableContinuation<Unit>,
    )

    private val inbox = ConcurrentLinkedQueue<Command>()
    private val ended = AtomicBoolean(false)

    /** Loop-thread confined: the parked waiters of each key. */
    private val waiters = IdentityHashMap<SelectionKey, MutableList<Waiter>>()

    /** Suspends until [channel] is ready for any of [ops] on this loop's selector. */
    suspend fun awaitReady(
        channel: SelectableChannel,
        ops: Int,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val waiter = Waiter(ops, continuation)
        continuation.invokeOnCancellation { submit(Command.Disarm(waiter)) }
        submit(Command.Arm(channel, waiter))
    }

    private fun submit(command: Command) {
        inbox.add(command)
        if (ended.get()) {
            // The loop has already made its final drain; nobody else will see this command.
            drainInbox(closedFailure())
        } else {
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
                selector.select()
                val selected = selector.selectedKeys().iterator()
                while (selected.hasNext()) {
                    val key = selected.next()
                    selected.remove()
                    dispatch(key)
                }
            }
        } catch (_: ClosedSelectorException) {
            // The socket closed its selector: the normal end of this loop.
        } finally {
            ended.set(true)
            loops.remove(selector, this)
            val failure = closedFailure()
            for (parked in waiters.values) parked.forEach { fail(it, failure) }
            waiters.clear()
            drainInbox(failure)
        }
    }

    private fun closedFailure() = SocketClosedException.General("Socket closed while waiting for it to be ready")

    /** Applies queued commands; with a [failure] (the loop is over) every Arm fails instead. */
    private fun drainInbox(failure: SocketClosedException?) {
        while (true) {
            when (val command = inbox.poll() ?: return) {
                is Command.Arm -> if (failure != null) fail(command.waiter, failure) else arm(command)
                is Command.Disarm -> if (failure == null) disarm(command.waiter)
            }
        }
    }

    private fun arm(command: Command.Arm) {
        val waiter = command.waiter
        if (!waiter.continuation.isActive) return
        try {
            // keyFor first: register() on a registered channel REPLACES the key's interest set.
            val key = command.channel.keyFor(selector) ?: command.channel.register(selector, 0)
            key.interestOps(key.interestOps() or waiter.ops)
            waiters.getOrPut(key) { ArrayList(2) }.add(waiter)
        } catch (closed: ClosedChannelException) {
            fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        } catch (closed: CancelledKeyException) {
            fail(waiter, SocketClosedException.General("Socket closed while waiting for it to be ready", closed))
        }
    }

    private fun disarm(waiter: Waiter) {
        val entry = waiters.entries.firstOrNull { waiter in it.value } ?: return
        entry.value.remove(waiter)
        val key = entry.key
        val wanted = entry.value.fold(0) { acc, other -> acc or other.ops }
        if (entry.value.isEmpty()) waiters.remove(key)
        try {
            if (key.isValid) key.interestOps(key.interestOps() and (waiter.ops and wanted.inv()).inv())
        } catch (_: CancelledKeyException) {
        }
    }

    private fun dispatch(key: SelectionKey) {
        val parked = waiters[key] ?: return // not a key of ours: nothing waits on it
        try {
            val ready = key.readyOps()
            val woken = parked.filter { it.ops and ready != 0 }
            parked.removeAll(woken.toSet())
            if (parked.isEmpty()) waiters.remove(key)
            val consumed = woken.fold(0) { acc, waiter -> acc or waiter.ops }
            key.interestOps(key.interestOps() and consumed.inv())
            woken.forEach { if (it.continuation.isActive) it.continuation.resume(Unit) }
        } catch (closed: CancelledKeyException) {
            waiters.remove(key)
            parked.forEach { fail(it, SocketClosedException.General("Socket closed while waiting for it to be ready", closed)) }
        }
    }

    private fun fail(
        waiter: Waiter,
        failure: SocketClosedException,
    ) {
        if (waiter.continuation.isActive) waiter.continuation.resumeWithException(failure)
    }

    companion object {
        private val loops = ConcurrentHashMap<Selector, SelectorLoop>()

        /** The loop that owns [selector], started on first use; a closed selector has none. */
        fun of(selector: Selector): SelectorLoop {
            if (!selector.isOpen) throw SocketClosedException.General("Socket closed while waiting for it to be ready")
            loops[selector]?.let { return it }
            val fresh = SelectorLoop(selector)
            val existing = loops.putIfAbsent(selector, fresh)
            if (existing != null) return existing
            Thread(fresh, "socket-nio-selector").apply { isDaemon = true }.start()
            return fresh
        }
    }
}
