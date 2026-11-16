@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.isActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/** Where a read stands between its caller and the connection. */
internal sealed interface ReadPhase {
    /** Not answered; the caller has not suspended. */
    data object Out : ReadPhase

    /** Not answered; the caller is suspended in [caller]. */
    class Waiting(
        val caller: Continuation<ReadAnswer>,
    ) : ReadPhase
}

/** What a read came to, set once; the caller turns it into its result on its own thread. */
internal sealed interface ReadAnswer : ReadPhase {
    /**
     * Bytes arrived in [buffer], which is the caller's from here on. Made when the read takes its buffer and held while
     * it waits, so the answer the connection hands a waiting caller is already made.
     */
    class Data(
        val buffer: PlatformBuffer,
    ) : ReadAnswer,
        Spare

    data object End : ReadAnswer

    data object Reset : ReadAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : ReadAnswer

    /** The caller's job was cancelled first: the connection takes the read back and frees what it would have handed over. */
    data object Cancelled : ReadAnswer
}

/** Whether an answer was the read's first, which the caller takes, or came after another and is dropped. */
internal enum class Landed { First, Late }

/**
 * The one atomic word a read's caller and the connection hand its answer over by: the connection answers once, the
 * caller's cancellation answers [ReadAnswer.Cancelled] when it comes first, and whichever answer lands resumes a waiting
 * caller. Answering allocates nothing.
 */
@Published
internal class ReadHand {
    private val phase = AtomicReference<ReadPhase>(ReadPhase.Out)

    fun load(): ReadPhase = phase.load()

    /** The caller: leaves [caller] for whoever answers, or returns the answer when it came first. */
    fun park(caller: Continuation<ReadAnswer>): Any {
        val waiting = ReadPhase.Waiting(caller)
        while (true) {
            when (val now = phase.load()) {
                is ReadAnswer -> return now
                is ReadPhase.Out, is ReadPhase.Waiting -> if (phase.compareAndSet(ReadPhase.Out, waiting)) return COROUTINE_SUSPENDED
            }
        }
    }

    /** Lands [answer] and resumes a waiting caller with it, unless another answer landed first. */
    fun answer(answer: ReadAnswer): Landed {
        while (true) {
            when (val now = phase.load()) {
                is ReadAnswer -> return Landed.Late
                is ReadPhase.Out -> if (phase.compareAndSet(now, answer)) return Landed.First
                is ReadPhase.Waiting ->
                    if (phase.compareAndSet(now, answer)) {
                        now.caller.resume(answer)
                        return Landed.First
                    }
            }
        }
    }

    fun callerLeft(): Boolean = phase.load() is ReadAnswer.Cancelled
}

/** What [answer] gives a caller whose context is [context]; a caller cancelled meanwhile frees the buffer and raises. */
internal fun resultOf(
    answer: ReadAnswer,
    context: CoroutineContext,
): ReadResult =
    when (answer) {
        is ReadAnswer.Data -> {
            if (!context.isActive) {
                answer.buffer.freeIfNeeded()
                raiseCancelled(context)
            }
            ReadResult.Data(answer.buffer)
        }
        is ReadAnswer.End -> if (context.isActive) ReadResult.End else raiseCancelled(context)
        is ReadAnswer.Reset -> if (context.isActive) ReadResult.Reset else raiseCancelled(context)
        is ReadAnswer.Failed -> raiseFailure(answer.failure, answer.during)
        is ReadAnswer.Cancelled -> raiseCancelled(context)
    }
