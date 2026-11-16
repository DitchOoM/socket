@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume

/** Who may touch a transfer's buffer. */
internal sealed interface Phase {
    /** Nobody: the buffer is at rest. */
    data object Idle : Phase

    /** A caller is encoding into or decoding from the buffer. */
    data object Using : Phase

    /** The connection owns the buffer: its operation is queued or parked, and the caller waits. */
    data object Armed : Phase

    /** The caller left while the operation was out; the connection owns the buffer until it has taken the operation back. */
    data object Abandoned : Phase

    /** The operation finished and left its outcome; the buffer is the caller's again. */
    data object Done : Phase

    /** The stream closed while a caller used the buffer; that caller frees it when it leaves. */
    data object Closing : Phase

    /** The buffer is freed. */
    data object Freed : Phase
}

/** What a settled operation left for its caller. */
internal sealed interface Outcome {
    data object Ok : Outcome

    class Failed(
        val failure: OpFailure,
    ) : Outcome
}

/** What [StreamTransfer.acquire] found. */
internal enum class Acquired {
    /** The buffer is the caller's. */
    Granted,

    /** A call that was cancelled has not been taken back by the connection yet: wait with [StreamTransfer.awaitSettled]. */
    Settling,
}

/** The one atomic word that says who may touch a transfer's buffer. */
@Published
internal class PhaseCell {
    private val word = AtomicReference<Phase>(Phase.Idle)

    fun load(): Phase = word.load()

    /** Moves from [from] to [to] when the word still says [from]. */
    fun moves(
        from: Phase,
        to: Phase,
    ): Boolean = word.compareAndSet(from, to)

    fun store(to: Phase) = word.store(to)
}

/** The waiter slot of a transfer nobody waits on. */
private object NoWaiter : Continuation<Unit> {
    override val context: CoroutineContext = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) = Unit
}

/** The one atomic slot a suspended caller leaves its continuation in, for the thread that settles the operation. */
@Published
internal class WaiterCell {
    private val slot = AtomicReference<Continuation<Unit>>(NoWaiter)

    /** Leaves [waiting] for whoever settles next. */
    fun leave(waiting: Continuation<Unit>) = slot.store(waiting)

    /** Takes [waiting] back, resuming it at once, when nothing settled it first. */
    fun resumeSelf(waiting: Continuation<Unit>) {
        if (takeBack(waiting)) waiting.resume(Unit)
    }

    /**
     * The caller: leaves [caller], intercepted, for whoever settles next; returns at once, unsuspended, when [settled]
     * says the wait is over and nothing took the waiter first.
     */
    inline fun park(
        caller: Continuation<Unit>,
        settled: () -> Boolean,
    ): Any {
        val resumable = caller.intercepted()
        leave(resumable)
        return if (settled() && takeBack(resumable)) Unit else COROUTINE_SUSPENDED
    }

    /** Takes [waiting] back unresumed; false when whoever settled took it first and resumes it. */
    fun takeBack(waiting: Continuation<Unit>): Boolean = slot.compareAndSet(waiting, NoWaiter)

    /** Resumes whoever waits. */
    fun resumeWaiting() {
        val waiting = slot.exchange(NoWaiter)
        if (waiting !== NoWaiter) waiting.resume(Unit)
    }
}
