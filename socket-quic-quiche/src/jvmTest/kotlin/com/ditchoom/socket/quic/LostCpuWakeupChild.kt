package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.exitProcess

/**
 * The debuggee half of [LostCpuWakeupTests]: a JVM started with one CPU permit
 * (`kotlinx.coroutines.scheduler.core.pool.size=1`), whose scheduler workers the test pauses through
 * JDI to force kotlinx.coroutines issue #4491's interleaving, step by step.
 *
 * Its one argument names the context a parked "driver loop" is resumed through:
 * - `default`: plain `Dispatchers.Default`, the control. The reproducer must lose the wakeup here, or it
 *   reproduces nothing.
 * - `production`: [productionDriverContext], what every JVM/Android QUIC driver loop runs on.
 *
 * The steps, each waiting until the scheduler is quiet before the next:
 * 1. A blocking task parks a worker inside `Dispatchers.IO`.
 * 2. With the debugger armed, a CPU task runs. The worker that ran it scans, finds nothing, and is held by
 *    the debugger in `tryPark`, just before it gives its CPU permit back. It is the only permit.
 * 3. The blocking task ends. Its worker cannot get a permit, finds no blocking work, and parks on top of
 *    the held worker in the parked-workers stack.
 * 4. A non-worker thread (this one, standing in for the timer thread, the NIO receive loop or a server
 *    handler on `Dispatchers.IO`) resumes the waiting loop. On `Dispatchers.Default` the task goes to the
 *    global CPU queue and the one worker signalled cannot take it: it has no permit.
 * 5. The held worker is released. It returns its permit and parks without looking at the queue.
 *
 * Exit code [RESUMED] when the waiting coroutine ran, [LOST] when it did not within [LOST_AFTER_MS];
 * anything else is a broken choreography and says which step on stdout.
 */
object LostCpuWakeupChild {
    /** Written by the debugger: the name of the worker it holds. */
    @JvmField
    @Volatile
    var heldWorker: String? = null

    /** A breakpoint the debugger sets: the child names the step it reached. */
    @JvmStatic
    fun mark(step: String) {
        println("step: $step")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val target: CoroutineContext =
            when (args.single()) {
                "default" -> Dispatchers.Default
                "production" -> productionDriverContext
                else -> error("unknown mode ${args.single()}")
            }
        val wake = Channel<Unit>(Channel.UNLIMITED)
        val resumed = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        CoroutineScope(target).launch {
            waiting.countDown()
            wake.receive()
            resumed.countDown()
        }
        check(waiting.await(5, TimeUnit.SECONDS)) { "the waiting coroutine never started" }
        awaitQuiet(except = null)

        // 1. A blocking task holding a worker in Dispatchers.IO.
        val blockingStarted = CountDownLatch(1)
        val blockingRelease = CountDownLatch(1)
        var blockingWorker: Thread? = null
        Dispatchers.IO.dispatch(
            EmptyCoroutineContext,
            Runnable {
                blockingWorker = Thread.currentThread()
                blockingStarted.countDown()
                blockingRelease.await()
            },
        )
        check(blockingStarted.await(5, TimeUnit.SECONDS)) { "the blocking task never started" }
        awaitQuiet(except = null)

        // 2. A CPU task; the worker that ran it is held with the only CPU permit, after its last scan.
        mark("arm")
        val cpuRan = CountDownLatch(1)
        Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable { cpuRan.countDown() })
        check(cpuRan.await(5, TimeUnit.SECONDS)) { "the CPU task never ran" }
        val held = awaitHeld()
        awaitQuiet(except = held)

        // 3. The blocking worker returns, finds no permit and parks on top of the held worker.
        blockingRelease.countDown()
        val returning = checkNotNull(blockingWorker)
        awaitParked(returning)
        awaitQuiet(except = held)
        println("scheduler with the permit held: ${schedulerState()}")

        // 4. A non-worker thread resumes the waiting loop.
        check(wake.trySend(Unit).isSuccess)
        Thread.sleep(SETTLE_MS)
        awaitQuiet(except = held)

        // 5. The held worker gives its permit back and parks.
        mark("release")
        Thread.sleep(SETTLE_MS)
        awaitQuiet(except = null)

        if (resumed.await(LOST_AFTER_MS, TimeUnit.MILLISECONDS)) {
            println("RESUMED: ${schedulerState()}")
            exitProcess(RESUMED)
        }
        println("LOST: ${schedulerState()}")
        // The capture's other half: any later dispatch wakes a worker, and the stranded task runs.
        Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable { })
        println("after an unrelated dispatch, resumed=${resumed.await(5, TimeUnit.SECONDS)}")
        exitProcess(LOST)
    }

    private fun awaitHeld(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            heldWorker?.let { return it }
            Thread.sleep(5)
        }
        println("BROKEN: the debugger never held a worker with the CPU permit")
        exitProcess(BROKEN)
    }

    private fun workers(): List<Thread> = Thread.getAllStackTraces().keys.filter { it.name.startsWith("DefaultDispatcher-worker-") }

    private fun parkedInScheduler(thread: Thread): Boolean =
        thread.state == Thread.State.TIMED_WAITING &&
            thread.stackTrace.any { it.className.endsWith("CoroutineScheduler\$Worker") && it.methodName == "park" }

    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (parkedInScheduler(thread)) return
            Thread.sleep(5)
        }
        println("BROKEN: ${thread.name} never parked: ${thread.state} ${thread.stackTrace.take(6)}")
        exitProcess(BROKEN)
    }

    /** Every scheduler worker but [except] parked or blocked, held stable over a few polls. */
    private fun awaitQuiet(except: String?) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var stablePolls = 0
        while (System.nanoTime() < deadline) {
            val busy = workers().filter { it.name != except && it.state == Thread.State.RUNNABLE }
            stablePolls = if (busy.isEmpty()) stablePolls + 1 else 0
            if (stablePolls >= 10) return
            Thread.sleep(5)
        }
        println("BROKEN: the scheduler never went quiet: ${workers().map { "${it.name}=${it.state}" }}")
        exitProcess(BROKEN)
    }

    private fun schedulerState(): String =
        try {
            val owner = Class.forName("kotlinx.coroutines.scheduling.DefaultScheduler")
            val instance = owner.getField("INSTANCE").get(null)
            val field =
                generateSequence<Class<*>>(owner) { it.superclass }
                    .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == "coroutineScheduler" } }
                    ?: return "unreadable"
            field.isAccessible = true
            field.get(instance).toString()
        } catch (t: Throwable) {
            "unreadable: $t"
        }

    const val RESUMED = 0
    const val LOST = 3
    const val BROKEN = 4
    private const val SETTLE_MS = 200L
    private const val LOST_AFTER_MS = 3_000L
}
