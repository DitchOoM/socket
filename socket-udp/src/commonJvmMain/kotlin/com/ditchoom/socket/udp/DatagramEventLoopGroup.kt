@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.udp

import java.util.concurrent.ThreadFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A fixed set of [DatagramEventLoop]s that connections are spread over, one loop per connection for
 * its whole life ([hold]). About one loop per core: each loop is one thread, so the group runs no more
 * transport work at once than the machine has cores, and an idle loop has no thread at all.
 *
 * @param size how many loops; their threads start only when a connection needs one.
 * @param namePrefix each loop's thread is named `<namePrefix>-<index>`.
 * @param linger how long a loop's thread outlives its last connection, so a process that reconnects
 *   does not start a thread per connection.
 */
@InternalDatagramLoopApi
class DatagramEventLoopGroup(
    size: Int,
    private val namePrefix: String,
    linger: Duration = DEFAULT_LINGER,
    threadFactory: ThreadFactory? = null,
) {
    init {
        require(size > 0) { "a loop group needs at least one loop, was $size" }
    }

    val loops: List<DatagramEventLoop> =
        List(size) { index ->
            val name = "$namePrefix-$index"
            DatagramEventLoop(name, linger.inWholeNanoseconds, threadFactory ?: DaemonLoopThreads(name))
        }

    /**
     * Holds the least held loop for one connection: the fewest holders, then one whose thread already
     * runs, then the lowest index. A loop whose thread cannot be started is passed over for one that
     * runs.
     *
     * [near] is a loop the connection exchanges every datagram with (a server's receive loop, which
     * routes each inbound datagram to the connection): it is taken while it is held by at most one more
     * than the least held loop. Sharing it saves a hand-off between threads per datagram; the bound keeps
     * a busy server's connections spreading over the other loops.
     *
     * @throws DatagramEventLoopUnavailableException when no loop runs and none can be started.
     */
    fun hold(near: DatagramEventLoop? = null): DatagramEventLoopHold {
        val ranked = loops.withIndex().sortedWith(compareBy({ it.value.holders }, { if (it.value.isRunning) 0 else 1 }, { it.index }))
        if (near != null && near.isRunning && near.holders <= ranked.first().value.holders + NEAR_SLACK) {
            try {
                return near.hold()
            } catch (_: DatagramEventLoopUnavailableException) {
                // Stopped meanwhile and could not start again: place it as if nothing were near.
            }
        }
        var failure: DatagramEventLoopUnavailableException? = null
        for ((_, loop) in ranked) {
            // After one start has failed, only a loop that already runs can be held.
            if (failure != null && !loop.isRunning) continue
            try {
                return loop.hold()
            } catch (unavailable: DatagramEventLoopUnavailableException) {
                failure = failure ?: unavailable
            }
        }
        throw failure ?: IllegalStateException("$namePrefix: no loop could be held")
    }

    private val nextIndex =
        java.util.concurrent.atomic
            .AtomicInteger()

    /**
     * A loop for something that only ever waits on it briefly (a channel used off any loop), without a
     * hold: round robin. A loop's thread keeps running while anything waits on it, and exits once
     * nothing does.
     */
    fun next(): DatagramEventLoop = loops[Math.floorMod(nextIndex.getAndIncrement(), loops.size)]

    /** The threads running this group's loops now. For tests and diagnostics. */
    fun runningThreads(): List<Thread> = loops.mapNotNull { it.runningThread }

    companion object {
        val DEFAULT_LINGER: Duration = 1.seconds

        /** How many more holders than the least held loop a [hold]'s `near` loop may have and still be taken. */
        private const val NEAR_SLACK = 1
    }
}

/** A loop's thread is a platform daemon thread: a selector needs a platform thread, and a loop must never keep a process alive. */
private class DaemonLoopThreads(
    private val name: String,
) : ThreadFactory {
    override fun newThread(task: Runnable): Thread = Thread(task, name).apply { isDaemon = true }
}
