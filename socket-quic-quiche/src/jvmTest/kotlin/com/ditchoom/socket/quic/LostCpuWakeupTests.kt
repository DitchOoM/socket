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
        Debugger(vm).run()
        check(vm.process().waitFor(60, TimeUnit.SECONDS)) { "the child never exited:\n$output" }
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
     */
    private class Debugger(
        private val vm: VirtualMachine,
    ) {
        private var armed = false
        private var held: ThreadReference? = null

        fun run() {
            // One request per class: filters on a single request must all match.
            for (name in listOf(WORKER, CHILD)) {
                vm.eventRequestManager().createClassPrepareRequest().apply {
                    addClassFilter(name)
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    enable()
                }
            }
            vm.resume()
            try {
                while (true) {
                    val events = vm.eventQueue().remove(TimeUnit.SECONDS.toMillis(60)) ?: error("the child went silent")
                    var resume = true
                    for (event in events) {
                        when (event) {
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
            val name = if (type.name() == WORKER) "tryReleaseCpu" else "mark"
            val method = type.methodsByName(name).single()
            vm.eventRequestManager().createBreakpointRequest(method.location()).apply {
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                enable()
            }
        }

        /** @return whether the stopped thread may run on. */
        private fun onBreakpoint(event: BreakpointEvent): Boolean {
            val thread = event.thread()
            val frame = thread.frame(0)
            if (frame.location().method().name() == "mark") {
                when ((frame.argumentValues.single() as StringReference).value()) {
                    "arm" -> armed = true
                    "release" -> held?.resume()
                }
                return true
            }
            if (!armed || held != null) return true
            if (thread
                    .frame(1)
                    .location()
                    .method()
                    .name() != "tryPark"
            ) {
                return true
            }
            val worker = frame.thisObject()
            val state = worker.getValue(worker.referenceType().fieldByName("state")) as ObjectReference
            val stateName = (state.getValue(state.referenceType().fieldByName("name")) as StringReference).value()
            if (stateName != "CPU_ACQUIRED") return true
            held = thread
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
