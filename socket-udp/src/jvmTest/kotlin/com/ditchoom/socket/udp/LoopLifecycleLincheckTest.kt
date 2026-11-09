package com.ditchoom.socket.udp

import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [LoopLifecycle], model checked: **a task is never left queued with no thread to run it, and a loop
 * never has two threads.**
 *
 * The threads are simulated by a count ([live]): a [LoopLifecycle.Next.Start] answer is a thread
 * started, and [LoopLifecycle.tryExit] returning true is the thread ending. The operations are the
 * calls the real loop makes, from the threads that make them:
 *
 * - any thread: [submit] a task (a dispatch from outside the loop), [hold] and [release] the loop (a
 *   connection starting and ending);
 * - the loop's thread, one at a time: [loopTurn], which drains the queue and, the loop now looking
 *   idle, offers to exit. It does nothing while no thread exists.
 *
 * After every interleaving the model checker explores, [noStrandedTaskAndOneThreadAtMost] checks that a
 * queued task has a thread, and that no more than one thread ever existed at once.
 *
 * [CheckThenStopLincheckTest] is the control: the exit with its two steps the other way round (look at
 * the queue, then stop), which loses a task submitted between the two. It MUST fail; if it passes, this
 * test has stopped exploring the race it exists for.
 */
abstract class LoopLifecycleModel {
    internal abstract val lifecycle: Lifecycle

    /** The protocol under test, so the control can supply a broken one. */
    internal interface Lifecycle {
        val tasks: ConcurrentLinkedQueue<Runnable>

        fun submit(task: Runnable): LoopLifecycle.Next

        fun acquire(): LoopLifecycle.Next

        fun release()

        fun tryExit(): Boolean
    }

    private val live = AtomicInteger()
    private val maxLive = AtomicInteger()
    private val holds = AtomicInteger()

    /**
     * Guards the model's own count of threads, not the protocol: a thread that [LoopLifecycle.tryExit]
     * let go runs no more loop code from that instant, so its end and a new thread's start are counted
     * in that order. The protocol's calls themselves stay outside it, free to interleave.
     */
    private val bookkeeping = Any()

    private fun started() {
        synchronized(bookkeeping) {
            val now = live.incrementAndGet()
            maxLive.accumulateAndGet(now, ::maxOf)
        }
    }

    @Operation
    fun submit() {
        if (lifecycle.submit(Runnable { }) == LoopLifecycle.Next.Start) started()
    }

    @Operation
    fun hold() {
        if (lifecycle.acquire() == LoopLifecycle.Next.Start) started()
        holds.incrementAndGet()
    }

    @Operation
    fun release() {
        // Only a hold that was taken can end.
        if (holds.getAndUpdate { if (it > 0) it - 1 else it } > 0) lifecycle.release()
    }

    @Operation(nonParallelGroup = LOOP)
    fun loopTurn() {
        if (live.get() == 0) return
        while (lifecycle.tasks.poll() != null) Unit
        synchronized(bookkeeping) { if (lifecycle.tryExit()) live.decrementAndGet() }
    }

    @Validate
    fun noStrandedTaskAndOneThreadAtMost() {
        check(maxLive.get() <= 1) { "two threads ran the loop at once" }
        check(lifecycle.tasks.isEmpty() || live.get() == 1) { "a task is queued and no thread will run it" }
    }

    private companion object {
        const val LOOP = "loop"
    }
}

class LoopLifecycleModelUnderTest : LoopLifecycleModel() {
    override val lifecycle: Lifecycle =
        object : Lifecycle {
            private val real = LoopLifecycle()
            override val tasks get() = real.tasks

            override fun submit(task: Runnable) = real.submit(task, onLoop = false)

            override fun acquire() = real.acquire()

            override fun release() = real.release()

            override fun tryExit() = real.tryExit()
        }
}

/** The control: [LoopLifecycle.tryExit] with its look at the queue BEFORE it stops. */
class CheckThenStopModel : LoopLifecycleModel() {
    override val lifecycle: Lifecycle =
        object : Lifecycle {
            private val lock = Any()

            @Volatile
            private var running = false
            private var holds = 0
            override val tasks = ConcurrentLinkedQueue<Runnable>()

            override fun submit(task: Runnable): LoopLifecycle.Next {
                tasks.add(task)
                if (running) return LoopLifecycle.Next.Wake
                synchronized(lock) {
                    if (running) return LoopLifecycle.Next.Wake
                    running = true
                    return LoopLifecycle.Next.Start
                }
            }

            override fun acquire(): LoopLifecycle.Next =
                synchronized(lock) {
                    holds++
                    if (running) {
                        LoopLifecycle.Next.Nothing
                    } else {
                        running = true
                        LoopLifecycle.Next.Start
                    }
                }

            override fun release() {
                synchronized(lock) { holds-- }
            }

            override fun tryExit(): Boolean =
                synchronized(lock) {
                    if (holds > 0 || tasks.isNotEmpty()) return false
                    running = false
                    true
                }
        }
}

/**
 * The race the protocol exists for, as a scenario of its own: a thread is running (the first submit
 * started it), and its turn ends with an offer to exit while another thread submits. The generated
 * scenarios add holds, releases and more submits around it.
 */
private fun lifecycleOptions(): ModelCheckingOptions =
    ModelCheckingOptions()
        .addCustomScenario {
            initial { actor(LoopLifecycleModel::submit) }
            parallel {
                thread { actor(LoopLifecycleModel::loopTurn) }
                thread { actor(LoopLifecycleModel::submit) }
            }
        }.addCustomScenario {
            initial {
                actor(LoopLifecycleModel::hold)
                actor(LoopLifecycleModel::release)
            }
            parallel {
                thread { actor(LoopLifecycleModel::loopTurn) }
                thread { actor(LoopLifecycleModel::hold) }
                thread { actor(LoopLifecycleModel::submit) }
            }
        }.threads(3)
        .actorsPerThread(2)
        .actorsBefore(1)
        .actorsAfter(0)
        .iterations(20)
        .invocationsPerIteration(500)

class LoopLifecycleLincheckTest {
    @Test
    fun noTaskIsStrandedAndNoLoopHasTwoThreads() {
        lifecycleOptions().check(LoopLifecycleModelUnderTest::class.java)
    }
}

class CheckThenStopLincheckTest {
    @Test
    fun theControlStrandsATask() {
        val failure =
            assertFailsWith<LincheckAssertionError> {
                lifecycleOptions().check(CheckThenStopModel::class.java)
            }
        // It must fail for the stranded task, not for anything else the model checks.
        assertTrue("a task is queued and no thread will run it" in failure.message.orEmpty(), failure.message)
    }
}
