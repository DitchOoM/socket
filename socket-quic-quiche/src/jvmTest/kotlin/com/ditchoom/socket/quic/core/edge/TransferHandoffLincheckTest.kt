package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The hand-over of a [StreamTransfer]'s buffer, modelled on the real [PhaseCell] and [WaiterCell] with no connection: a
 * caller's call arms the operation and waits; the connection settles it, withdraws an abandoned one, or releases it;
 * the caller's job is cancelled. A wait watches the job as the real one does: the watch is installed before the wait
 * and fires once, at once when the job was already cancelled, and its handler abandons an operation still out and
 * resumes the waiter, which then raises the cancellation. A resumed wait runs the caller's next step on the resuming
 * thread, as a coroutine resumed without a dispatcher does.
 *
 * The audit after every interleaving: no resumed wait found the buffer still out (the caller would have been told its
 * stream was closed while it was not), no wait is left suspended once the operation came back, and the buffer was freed
 * at most once. With [loopsOnPhase] false a wait trusts its resume, as the first version did: a settling thread slow to
 * resume its waiter then resumes the next call's, and the model must catch it.
 */
abstract class TransferHandoffModel(
    private val loopsOnPhase: Boolean,
) {
    private val phase = PhaseCell()
    private val waiting = WaiterCell()
    private val withdrawPending = AtomicInteger()
    private val spurious = AtomicInteger()
    private val freed = AtomicInteger()

    /** 1 while a call has begun and not yet returned or raised: one call at a time, as one coroutine makes them. */
    private val active = AtomicInteger()

    /** 1 once the caller's job is cancelled. */
    private val jobCancelled = AtomicInteger()

    /** The job watch of the wait in progress: none, installed, or fired (its handler ran, once). */
    private val watch = AtomicInteger(NO_WATCH)

    /** One suspended wait, resumed once. */
    private inner class Wait : Continuation<Unit> {
        private val state = AtomicInteger(PENDING)
        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            if (state.compareAndSet(PENDING, RESUMED)) woke()
        }
    }

    /** A caller's call: takes the buffer, arms the operation, and takes the result back at once or when resumed. */
    @Operation(nonParallelGroup = "caller")
    fun call() {
        if (!active.compareAndSet(0, 1)) return
        val taken = phase.moves(Phase.Idle, Phase.Using) || phase.moves(Phase.Done, Phase.Using)
        if (!taken) return active.set(0)
        if (!phase.moves(Phase.Using, Phase.Armed)) {
            free()
            return active.set(0)
        }
        if (phase.load() is Phase.Done) finish() else await()
    }

    /** The caller's job is cancelled: a watch installed fires. */
    @Operation(nonParallelGroup = "canceller")
    fun cancel() {
        jobCancelled.set(1)
        if (watch.compareAndSet(WATCHING, FIRED)) abandon()
    }

    /** The connection finishes the operation. */
    @Operation(nonParallelGroup = "connection")
    fun settle() {
        if (phase.moves(Phase.Armed, Phase.Done)) waiting.resumeWaiting()
    }

    /** The connection takes back an abandoned operation. */
    @Operation(nonParallelGroup = "connection")
    fun withdraw() {
        if (withdrawPending.compareAndSet(1, 0) && phase.moves(Phase.Abandoned, Phase.Idle)) waiting.resumeWaiting()
    }

    /** The stream closes: its buffer is freed once nothing can touch it. */
    @Operation(nonParallelGroup = "connection")
    fun close() {
        while (true) {
            when (val now = phase.load()) {
                is Phase.Idle, is Phase.Done -> if (phase.moves(now, Phase.Freed)) return free()
                is Phase.Using -> if (phase.moves(now, Phase.Closing)) return
                is Phase.Armed -> if (phase.moves(now, Phase.Done)) waiting.resumeWaiting()
                is Phase.Abandoned ->
                    if (phase.moves(now, Phase.Freed)) {
                        free()
                        return waiting.resumeWaiting()
                    }
                is Phase.Closing, is Phase.Freed -> return
            }
        }
    }

    /** After every thread has finished. A call still waiting while its operation is no longer out was never woken. */
    @Validate
    fun validate() {
        val stranded = active.get() == 1 && phase.load() !is Phase.Armed
        check(spurious.get() == 0 && freed.get() <= 1 && !stranded)
    }

    /** The watch's handler: an operation still out is abandoned, and its waiter resumed to raise the cancellation. */
    private fun abandon() {
        if (phase.moves(Phase.Armed, Phase.Abandoned)) {
            withdrawPending.set(1)
            waiting.resumeWaiting()
        }
    }

    /** The wait: installs the watch (which fires at once on a cancelled job), leaves the waiter, and takes it back when settled already. */
    private fun await() {
        watch.set(WATCHING)
        if (jobCancelled.get() == 1 && watch.compareAndSet(WATCHING, FIRED)) abandon()
        val wait = Wait()
        waiting.leave(wait)
        if (phase.load() !is Phase.Armed && waiting.takeBack(wait)) woke()
    }

    /** A wait ended: the watch is disposed; a cancelled caller raises, any other looks at the phase again. */
    private fun woke() {
        watch.compareAndSet(WATCHING, NO_WATCH)
        if (jobCancelled.get() == 1) return active.set(0)
        if (loopsOnPhase && phase.load() is Phase.Armed) await() else finish()
    }

    /** What the caller does once the operation settled: takes the buffer back and leaves it, or finds the stream closed. */
    private fun finish() {
        if (!phase.moves(Phase.Done, Phase.Using)) {
            if (phase.load() !is Phase.Freed) spurious.incrementAndGet()
        } else if (!phase.moves(Phase.Using, Phase.Idle)) {
            free()
        }
        active.set(0)
    }

    private fun free() {
        freed.incrementAndGet()
        phase.store(Phase.Freed)
    }

    protected fun options(): ModelCheckingOptions =
        ModelCheckingOptions()
            .sequentialSpecification(TransferHandoffSpec::class.java)
            .threads(3)
            .actorsPerThread(3)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(60))
            .invocationsPerIteration(LincheckBudget.scaled(4_000))

    private companion object {
        const val PENDING = 0
        const val RESUMED = 1
        const val NO_WATCH = 0
        const val WATCHING = 1
        const val FIRED = 2
    }
}

class TransferHandoffLincheckTest : TransferHandoffModel(loopsOnPhase = true) {
    @Test
    fun aResumedWaitNeverFindsTheBufferStillOutNoWaitIsStrandedAndTheBufferIsFreedOnce() {
        options().check(this::class)
    }
}

/** The control: a wait that trusts its resume. A settler slow to resume resumes the next call's wait; the model must catch it. */
class TransferHandoffTrustingWaitLincheckTest : TransferHandoffModel(loopsOnPhase = false) {
    @Test
    fun theModelCatchesAWaitThatTrustsAResumeThatBelongedToTheCallBefore() {
        assertFailsWith<LincheckAssertionError> { options().check(this::class) }
    }
}

class TransferHandoffSpec {
    fun call() = Unit

    fun cancel() = Unit

    fun settle() = Unit

    fun withdraw() = Unit

    fun close() = Unit
}
