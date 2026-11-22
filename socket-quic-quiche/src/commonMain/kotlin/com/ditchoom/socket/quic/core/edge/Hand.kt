@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonDisposableHandle
import kotlinx.coroutines.isActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.jvm.JvmInline

/**
 * Where an operation stands between its caller and the connection; an answer that landed is a phase of its own. A phase
 * takes answers in ([A] is contravariant), so a waiting caller is resumed with exactly the answer type it waits for.
 */
internal sealed interface HandPhase<in A : HandAnswer> {
    /** Not answered; the caller has not suspended. */
    data object Out : HandPhase<HandAnswer>

    /** Not answered; the caller is suspended in [caller]. */
    class Waiting<A : HandAnswer>(
        val caller: Continuation<A>,
    ) : HandPhase<A>
}

/** What an operation came to, set once; the caller turns it into its result on its own thread. */
internal sealed interface HandAnswer : HandPhase<HandAnswer>

/** The caller made its result from the answer that landed: nothing it was handed is in flight any more. */
internal data object AnswerTaken : HandAnswer

/** Whether an answer was the operation's first, which the caller takes, or came after another and is dropped. */
internal enum class Landed { First, Late }

/**
 * The one atomic word an operation's caller and the connection hand its answer over by: the first answer lands, and
 * landing it resumes a waiting caller with it. The answers are made before they land, so answering allocates nothing;
 * the caller builds its result from the answer on its own thread. A value class: the operation holds the word itself.
 */
@Published
@JvmInline
internal value class Hand<A : HandAnswer> private constructor(
    private val phase: AtomicReference<HandPhase<A>>,
) {
    constructor() : this(AtomicReference(HandPhase.Out))

    /** Whether no answer has landed yet. */
    fun isOpen(): Boolean =
        when (phase.load()) {
            is HandPhase.Out, is HandPhase.Waiting -> true
            is HandAnswer -> false
        }

    /**
     * The caller: returns the answer when it landed first, without intercepting [caller]; else leaves the intercepted
     * [caller] for whoever answers.
     */
    fun take(caller: Continuation<A>): Any {
        val first = phase.load()
        if (first is HandAnswer) return first
        val waiting = HandPhase.Waiting(caller.intercepted())
        while (true) {
            when (val now = phase.load()) {
                is HandAnswer -> return now
                is HandPhase.Out, is HandPhase.Waiting -> if (phase.compareAndSet(HandPhase.Out, waiting)) return COROUTINE_SUSPENDED
            }
        }
    }

    /** Lands [answer] and resumes a waiting caller with it, unless another answer landed first. */
    fun answer(answer: A): Landed {
        while (true) {
            when (val now = phase.load()) {
                is HandAnswer -> return Landed.Late
                is HandPhase.Out -> if (phase.compareAndSet(now, answer)) return Landed.First
                is HandPhase.Waiting ->
                    if (phase.compareAndSet(now, answer)) {
                        now.caller.resume(answer)
                        return Landed.First
                    }
            }
        }
    }

    /**
     * Lands [answer] and, when it is the first, runs [ahead] before a waiting caller resumes with it: what [ahead] queues
     * is ahead of anything the resumed caller does next.
     */
    fun answer(
        answer: A,
        ahead: () -> Unit,
    ): Landed {
        while (true) {
            when (val now = phase.load()) {
                is HandAnswer -> return Landed.Late
                is HandPhase.Out ->
                    if (phase.compareAndSet(now, answer)) {
                        ahead()
                        return Landed.First
                    }
                is HandPhase.Waiting ->
                    if (phase.compareAndSet(now, answer)) {
                        try {
                            ahead()
                        } finally {
                            now.caller.resume(answer)
                        }
                        return Landed.First
                    }
            }
        }
    }

    /** Whether [answer] is the one that landed. */
    fun landed(answer: A): Boolean = phase.load() === answer

    /** Where the operation stands now. */
    fun current(): HandPhase<A> = phase.load()

    /** The caller, once it made its result from the answer that landed: it no longer holds what the answer handed. */
    fun taken() = phase.store(AnswerTaken)
}

/**
 * The caller: the operation's answer, at once when it landed, else once it lands. While it waits the caller's job in
 * [context] is watched, and [cancelled] runs on the cancelling thread if the job is cancelled; the watch is made only then.
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend inline fun <A : HandAnswer> Hand<A>.await(
    context: CoroutineContext,
    crossinline cancelled: () -> Unit,
): A {
    if (!isOpen()) return suspendCoroutineUninterceptedOrReturn { take(it) }
    val watch = context[Job]?.invokeOnCompletion(onCancelling = true) { _ -> cancelled() } ?: NonDisposableHandle
    try {
        return suspendCoroutineUninterceptedOrReturn { take(it) }
    } finally {
        watch.dispose()
    }
}

/** What a read came to. */
internal sealed interface ReadAnswer : HandAnswer {
    /**
     * Bytes arrived in [buffer], which is the caller's from here on. Made when the read takes its buffer and held while
     * it waits, so the answer the connection hands a waiting caller is already made.
     */
    class Data(
        val buffer: PlatformBuffer,
    ) : ReadAnswer,
        Spare,
        Idle

    data object End : ReadAnswer

    /** The peer reset its side of the stream with [code]. */
    class Reset(
        val code: QuicAppErrorCode,
    ) : ReadAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : ReadAnswer

    /** The caller's job was cancelled first: the connection takes the read back and frees what it would have handed over. */
    data object Cancelled : ReadAnswer
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

/** What a write came to. */
internal sealed interface WriteAnswer : HandAnswer {
    /** quiche took bytes, or our FIN: the source's position moved past what it took before this landed. */
    data object Written : WriteAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : WriteAnswer

    /** The connection took the write back after its caller's job was cancelled; it touches the source no more. */
    data object Cancelled : WriteAnswer
}

/** What an open came to. */
internal sealed interface OpenAnswer : HandAnswer {
    /** The stream the open made; the caller wraps it. */
    data object Opened : OpenAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : OpenAnswer

    /** The caller's job was cancelled first: the connection takes the open back. */
    data object Cancelled : OpenAnswer
}
