package com.ditchoom.socket.quic

import com.sun.jdi.Bootstrap
import com.sun.jdi.ClassType
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.LaunchingConnector
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import com.sun.jdi.request.EventRequest
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A QUIC driver loop is never resumed through the coroutine scheduler's CPU queue, because that queue can
 * lose the wakeup.**
 *
 * kotlinx.coroutines 1.11.0's `CoroutineScheduler` has a race in `Worker.tryPark` (kotlinx.coroutines issue
 * #4491, fix proposed in PR #4528, unreleased): a worker that holds a CPU permit makes its last scan, a
 * second worker parks on top of it in the parked-workers stack, a task arrives in the global CPU queue from
 * a thread that is not a CPU worker, and `signalCpuWork` unparks the second worker — which cannot run the
 * task, because the first still holds the permit. The first then returns its permit and parks without
 * looking again. The task sits in the queue, every worker is parked, and nothing runs it until some
 * unrelated dispatch wakes a worker.
 *
 * That is the stall #799's flight recorder caught on Android (API 35 and 36, core pool 2): the server's
 * echo handler, on `Dispatchers.IO`, queued its write and resumed the driver loop, whose continuation went
 * to `Dispatchers.Default`'s global CPU queue (`global CPU queue size = 1`, `CPUs acquired = 0`, 18 workers
 * parked) and stayed there for 16 s, until the stall snapshot's own command dispatched something else.
 * Every wakeup of a driver loop comes from a thread that is not a CPU worker: the deadline timer thread,
 * the NIO receive loops and the handlers on `Dispatchers.IO`, an application's own threads.
 *
 * The race needs a thread to be paused at one exact instruction, so this test does it with a debugger: it
 * launches [LostCpuWakeupChild] under JDI with one CPU permit, holds the permit-owning worker at the entry of
 * `tryReleaseCpu` called from `tryPark`, walks the other steps, then lets it go. The interleaving is the
 * same on every run; no timing has to line up.
 *
 * - `Dispatchers.Default` (the control) loses the wakeup. If it ever stops doing so, kotlinx.coroutines
 *   has fixed the race (or changed the scheduler enough that this reproducer no longer reaches it), and
 *   the choice of [productionDriverContext] can be revisited.
 * - [productionDriverContext] does not: its tasks are blocking tasks, which any unparked worker runs
 *   without a CPU permit.
 */
class LostCpuWakeupTests {
    @Test
    fun theControlLosesTheWakeupOnDispatchersDefault() {
        val run = runChild("default")
        assertEquals(
            LostCpuWakeupChild.LOST,
            run.exitCode,
            "the forced interleaving did not strand a Dispatchers.Default task, so this reproducer no longer " +
                "reproduces kotlinx.coroutines #4491 and proves nothing about productionDriverContext:\n${run.output}",
        )
    }

    @Test
    fun aDriverLoopIsResumedUnderTheSameInterleaving() {
        val run = runChild("production")
        assertEquals(
            LostCpuWakeupChild.RESUMED,
            run.exitCode,
            "a driver loop resumed from a non-worker thread was stranded in the scheduler with every worker " +
                "parked — the stall an Android QUIC stream showed for 16 s:\n${run.output}",
        )
    }

    private class ChildRun(
        val exitCode: Int,
        val output: String,
    )

    private fun runChild(mode: String): ChildRun {
        val connector: LaunchingConnector =
            Bootstrap.virtualMachineManager().launchingConnectors().first { it.name() == "com.sun.jdi.CommandLineLaunch" }
        val arguments = connector.defaultArguments()
        arguments.getValue("home").setValue(System.getProperty("java.home"))
        arguments.getValue("main").setValue("${LostCpuWakeupChild::class.java.name} $mode")
        arguments.getValue("options").setValue(
            listOf(
                "-cp",
                System.getProperty("java.class.path"),
                "-Dkotlinx.coroutines.scheduler.core.pool.size=1",
                "-Dkotlinx.coroutines.scheduler.keep.alive.sec=3600",
            ).joinToString(" "),
        )
        val vm = connector.launch(arguments)
        val output = StringBuffer()
        val drains = listOf(vm.process().inputStream, vm.process().errorStream).map { drain(it, output) }
        val debugger = Debugger(vm)
        try {
            debugger.run()
            check(vm.process().waitFor(60, TimeUnit.SECONDS)) { "the child never exited" }
        } catch (t: Throwable) {
            // Failure-only: what the child printed, and every stop the debugger saw, in order.
            vm.process().destroyForcibly()
            drains.forEach { it.join(5_000) }
            throw AssertionError(
                "the $mode run's debugger failed: $t\n" +
                    "debugger stops:\n${debugger.history.joinToString("\n")}\nchild output:\n$output",
                t,
            )
        }
        drains.forEach { it.join(5_000) }
        println("[lost-cpu-wakeup] $mode:\n$output")
        return ChildRun(vm.process().exitValue(), output.toString())
    }

    private fun drain(
        stream: InputStream,
        into: StringBuffer,
    ): Thread =
        thread(isDaemon = true, name = "lost-wakeup-child-output") {
            stream.bufferedReader().forEachLine { into.append(it).append('\n') }
        }

    /**
     * Holds the first worker that reaches `tryReleaseCpu` from `tryPark` with the CPU permit once the child
     * marks "arm", and lets it go when the child marks "release". Every other stop resumes at once.
     *
     * It reads a stopped thread's frames only while the child cannot be exiting: a worker between "arm" and
     * the hold (the child's main thread is then waiting for the hold), and the child's main thread at a mark
     * (it is the thread that would exit). Once a worker is held, the `tryReleaseCpu` breakpoint is deleted, so
     * no worker stops again. While it stayed, a worker that parked after the child's last step stopped there while
     * the child was exiting, and JDWP had already dropped the thread's suspension, so reading its frame threw
     * `IncompatibleThreadStateException` (the JDK 17 / JNI lane, PR #815's run 42633926330, attempt 1).
     */
    private class Debugger(
        private val vm: VirtualMachine,
    ) {
        private var armed = false
        private var held: ThreadReference? = null
        private var workerBreakpoint: EventRequest? = null

        /** Every stop, for the failure message. */
        val history = mutableListOf<String>()

        fun run() {
            // One request per class: filters on a single request must all match.
            for (name in listOf(WORKER, CHILD)) {
                vm.eventRequestManager().createClassPrepareRequest().apply {
                    addClassFilter(name)
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    enable()
                }
            }
            // The launched VM waits, suspended, for its VMStartEvent to be resumed; that resume is the only
            // one. (An extra vm.resume() here would be a second, stale resume: it would release whatever
            // thread the first class-prepare stop had suspended.)
            try {
                while (true) {
                    val events = vm.eventQueue().remove(TimeUnit.SECONDS.toMillis(60)) ?: error("the child went silent")
                    var resume = true
                    for (event in events) {
                        when (event) {
                            is VMStartEvent -> history += "vm start"
                            is ClassPrepareEvent -> breakOn(event.referenceType() as ClassType)
                            is BreakpointEvent -> resume = onBreakpoint(event) && resume
                            is VMDeathEvent, is VMDisconnectEvent -> return
                        }
                    }
                    if (resume) events.resume()
                }
            } catch (_: com.sun.jdi.VMDisconnectedException) {
                // The child exited.
            }
        }

        private fun breakOn(type: ClassType) {
            history += "prepared ${type.name()}"
            val name = if (type.name() == WORKER) "tryReleaseCpu" else "mark"
            val method = type.methodsByName(name).single()
            val request =
                vm.eventRequestManager().createBreakpointRequest(method.location()).apply {
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    enable()
                }
            if (type.name() == WORKER) workerBreakpoint = request
        }

        /** @return whether the stopped thread may run on. */
        private fun onBreakpoint(event: BreakpointEvent): Boolean {
            val thread = event.thread()
            // The event's own location: reading it needs nothing from the thread.
            if (event.location().method().name() == "mark") {
                val step = (thread.frame(0).argumentValues.single() as StringReference).value()
                history += "mark $step on ${thread.name()}"
                when (step) {
                    "arm" -> armed = true
                    "release" -> held?.resume()
                }
                return true
            }
            if (!armed || held != null) return true
            val caller =
                thread
                    .frame(1)
                    .location()
                    .method()
                    .name()
            val worker = thread.frame(0).thisObject()
            val state = worker.getValue(worker.referenceType().fieldByName("state")) as ObjectReference
            val stateName = (state.getValue(state.referenceType().fieldByName("name")) as StringReference).value()
            history += "tryReleaseCpu on ${thread.name()} from $caller in $stateName"
            if (caller != "tryPark" || stateName != "CPU_ACQUIRED") return true
            held = thread
            // Nothing after the hold needs a worker stop; a later one could land while the child exits.
            workerBreakpoint?.let { vm.eventRequestManager().deleteEventRequest(it) }
            history += "holding ${thread.name()}"
            val child = vm.classesByName(CHILD).single() as ClassType
            child.setValue(child.fieldByName("heldWorker"), vm.mirrorOf(thread.name()))
            return false
        }

        private companion object {
            const val WORKER = "kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker"
            val CHILD: String = LostCpuWakeupChild::class.java.name
        }
    }
}
