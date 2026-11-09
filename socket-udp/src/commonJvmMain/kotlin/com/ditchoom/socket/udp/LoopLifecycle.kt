package com.ditchoom.socket.udp

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The part of a [DatagramEventLoop]'s life that every thread touches: its task queue, whether a thread
 * is running it, and how many connections hold it. Kept apart from the thread and the selector so the
 * one property that matters can be model checked on its own (`LoopLifecycleLincheckTest`):
 *
 * **No task is ever left in the queue with no thread to run it.**
 *
 * A loop's thread exits when it has been idle for a while and nothing holds it ([tryExit]), and the next
 * [submit] or [acquire] starts a new one. The exit and a submit from another thread race; the protocol
 * that settles the race is in [tryExit]. A task submitted while the thread is exiting is either seen by
 * the exiting thread, which then stays, or finds the loop stopped and starts a new thread itself.
 *
 * The caller acts on what each call returns; this class starts and wakes nothing.
 */
internal class LoopLifecycle {
    /** What the caller of [submit] or [acquire] must do next. */
    enum class Next {
        /** Nothing: the loop's own thread will run the task, or the loop is already running. */
        Nothing,

        /** The loop's thread may be parked in `select()`: wake it. */
        Wake,

        /** No thread runs the loop: the caller must start one, and report a failure to [startFailed]. */
        Start,
    }

    private val lock = Any()

    /** Whether a thread runs, or is about to run, the loop. Written under [lock]; read without it by [submit]. */
    @Volatile
    var running: Boolean = false
        private set

    /** Connections (and servers) that hold this loop; a held loop's thread does not exit. Guarded by [lock]. */
    private var holds = 0

    val tasks = ConcurrentLinkedQueue<Runnable>()

    /** How many hold the loop now; a placement reads it to find the least loaded loop. */
    val holders: Int get() = synchronized(lock) { holds }

    /**
     * Queues [task]. [onLoop] is whether the caller is the loop's own thread, which drains the queue
     * before it next parks and so needs no wake.
     */
    fun submit(
        task: Runnable,
        onLoop: Boolean,
    ): Next {
        tasks.add(task)
        if (onLoop) return Next.Nothing
        // Read AFTER the add: a thread that has decided to exit sets `running` false before it looks at
        // the queue a last time (see tryExit), so either it sees this task or this sees it stopped.
        if (running) return Next.Wake
        synchronized(lock) {
            if (running) return Next.Wake // started or kept alive meanwhile
            running = true
            return Next.Start
        }
    }

    /** One more connection holds the loop. [Next.Start] when no thread runs it. */
    fun acquire(): Next =
        synchronized(lock) {
            holds++
            if (running) {
                Next.Nothing
            } else {
                running = true
                Next.Start
            }
        }

    /** A hold taken by [acquire] ends. */
    fun release() {
        synchronized(lock) {
            check(holds > 0) { "a loop released more holds than it gave" }
            holds--
        }
    }

    /**
     * The start a [Next.Start] asked for could not happen (no thread could be created). The loop is
     * stopped again, and every queued task is handed back: nothing else would ever run them.
     */
    fun startFailed(): List<Runnable> =
        synchronized(lock) {
            running = false
            generateSequence { tasks.poll() }.toList()
        }

    /** A hold that [acquire] took before its start failed is given back without the release check. */
    fun abandonHold() {
        synchronized(lock) { if (holds > 0) holds-- }
    }

    /**
     * Called by the loop's thread when it has been idle long enough: true when the thread must now
     * exit, false when it must keep running because a task arrived or a connection holds the loop.
     *
     * `running` goes false BEFORE the last look at the queue. A [submit] that added its task before
     * that look is seen here, and the thread stays. One that added it after reads `running` after its
     * add, finds it false, and starts a new thread.
     */
    fun tryExit(): Boolean =
        synchronized(lock) {
            if (holds > 0) return false
            running = false
            if (tasks.isNotEmpty()) {
                running = true
                return false
            }
            true
        }
}
