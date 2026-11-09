@file:OptIn(InternalCoroutinesApi::class)

package com.ditchoom.socket.udp

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * SPIKE (perf/owned-loop-spike), measurement only, never merged.
 *
 * A Netty-style event loop as a [CoroutineDispatcher]: ONE thread that owns ONE [Selector] and runs, in
 * turn, the socket readiness of every channel waiting on it ([awaitReadable]), its timers ([Delay]) and
 * the coroutine tasks dispatched to it. A datagram channel's receive running on this loop parks in the
 * loop's selector instead of in a per-channel select() on Dispatchers.IO, so no selector thread and no
 * hand-off from a selector thread exist.
 */
class SpikeSelectorLoop(
    name: String,
) : CoroutineDispatcher(),
    Delay {
    val selector: Selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val wakeupPending = AtomicBoolean(false)
    private val timers = PriorityQueue<Timer>(compareBy { it.deadline }) // loop thread only
    private var seq = 0L

    private class Timer(
        val deadline: Long,
        val block: Runnable,
    ) : DisposableHandle {
        @Volatile var cancelled = false

        override fun dispose() {
            cancelled = true
        }
    }

    val thread: Thread = Thread(::loop, name).apply { isDaemon = true }

    init {
        thread.start()
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.add(block)
        if (Thread.currentThread() !== thread && wakeupPending.compareAndSet(false, true)) selector.wakeup()
    }

    /** Park until [channel] is readable, in this loop's selector. Must be called on this loop's thread. */
    suspend fun awaitReadable(channel: SelectableChannel) {
        check(Thread.currentThread() === thread) { "awaitReadable off its loop" }
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            val key = channel.keyFor(selector) ?: channel.register(selector, 0)
            key.attach(cont)
            key.interestOps(SelectionKey.OP_READ)
            cont.invokeOnCancellation {
                dispatch(cont.context) {
                    if (key.isValid && key.attachment() === cont) {
                        key.interestOps(0)
                        key.attach(null)
                    }
                }
            }
        }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val handle = invokeOnTimeout(timeMillis, { continuation.resume(Unit) }, continuation.context)
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val timer = Timer(System.nanoTime() + timeMillis.coerceAtMost(Long.MAX_VALUE / 4_000_000) * 1_000_000, block)
        if (Thread.currentThread() === thread) timers.add(timer) else dispatch(context) { timers.add(timer) }
        return timer
    }

    private fun loop() {
        while (true) {
            wakeupPending.set(false)
            var now = System.nanoTime()
            while (true) {
                val t = timers.peek() ?: break
                if (t.cancelled) {
                    timers.poll()
                    continue
                }
                if (t.deadline > now) break
                timers.poll()
                t.block.run()
            }
            if (tasks.isEmpty()) {
                val next = timers.peek()
                if (next == null) {
                    selector.select()
                } else {
                    now = System.nanoTime()
                    selector.select(((next.deadline - now) / 1_000_000).coerceAtLeast(1))
                }
            } else {
                selector.selectNow()
            }
            val keys = selector.selectedKeys()
            if (keys.isNotEmpty()) {
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    @Suppress("UNCHECKED_CAST")
                    val cont = key.attachment() as CancellableContinuation<Unit>? ?: continue
                    if (key.isValid) key.interestOps(0)
                    key.attach(null)
                    cont.resume(Unit)
                }
            }
            // Run tasks until the queue is empty (bounded), so a chain of resumes costs one selectNow, not one each.
            var n = 0
            while (n++ < MAX_TASKS_PER_TURN) {
                val task = tasks.poll() ?: break
                task.run()
            }
        }
    }

    override fun toString(): String = thread.name

    private companion object {
        const val MAX_TASKS_PER_TURN = 1024
    }
}
