@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.currentThreadId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Where a connection's stream handlers run. SPIKE. */
internal sealed interface HandlerPlacement {
    /** On the connection's loop thread, in the pass's handler phase. */
    data object OnLoop : HandlerPlacement

    /** On [dispatcher]; today's behaviour. */
    class OffLoop(
        val dispatcher: CoroutineDispatcher,
    ) : HandlerPlacement
}

/** Which handler a continuation belongs to, so a stall or failure names it. */
internal sealed class HandlerSite : AbstractCoroutineContextElement(HandlerSite) {
    companion object Key : CoroutineContext.Key<HandlerSite>

    /** A handler of [stream] on the connection [connection]. */
    class Stream(
        val connection: String,
        val stream: QuicStreamId,
    ) : HandlerSite() {
        override fun toString(): String = "$connection/${stream.id}"
    }

    /** The accept loop of [connection]. */
    class Accepting(
        val connection: String,
    ) : HandlerSite() {
        override fun toString(): String = "$connection/accept"
    }

    /** A continuation that carries no site. */
    data object Unnamed : HandlerSite()
}

/** Why a handler failed. */
internal sealed interface HandlerFailure {
    class Threw(
        val thrown: Exception,
    ) : HandlerFailure
}

/** What the loop saw a handler do. Emitted on the loop thread (or the thread that ran the handler). */
internal sealed interface HandlerObservation {
    /** One run of [site] held the loop for [heldNanos], over [budgetNanos]. */
    class Stalled(
        val site: HandlerSite,
        val heldNanos: Long,
        val budgetNanos: Long,
    ) : HandlerObservation

    /** [site] ended with [failure]; its stream was reset with [HANDLER_FAILED]. */
    class Failed(
        val site: HandlerSite,
        val failure: HandlerFailure,
    ) : HandlerObservation
}

/** Receives [HandlerObservation]s. */
internal fun interface HandlerSink {
    fun observed(observation: HandlerObservation)

    companion object {
        val Ignore = HandlerSink { }
    }
}

/** Whether any handler was placed on this loop: until then the loop's passes are exactly today's. */
internal enum class HandlerMode { Idle, Active }

/** Whether the pass runs another handler round. */
internal enum class Round { Due, Done }

private enum class HandlerLife { Running, Stopped }

/** The stream reset code a handler that threw leaves its stream with. */
internal const val HANDLER_FAILED = 0x5e1fL

/**
 * The loop as a coroutine dispatcher. A continuation resumed on the loop thread waits in [ready] for the pass's handler
 * phase, never inside quiche's processing; one resumed on another thread is posted through the loop's port. Each run is
 * timed against [stallBudgetNanos]. Loop-thread only, except [dispatch], [observe] and [activate]. SPIKE.
 */
@LoopOnly
internal class LoopHandlers(
    private val port: LoopPort,
) : CoroutineDispatcher() {
    private val loopThread = AtomicLong(NO_THREAD)
    private val mode = AtomicInt(HandlerMode.Idle.ordinal)
    private val sink = AtomicReference(HandlerSink.Ignore)
    private var life = HandlerLife.Running
    private val ready = ReadyRing()
    private var held = 0
    private var rounds = 0

    /** Stall budget for one handler run. */
    var stallBudgetNanos = DEFAULT_STALL_BUDGET_NANOS

    /** Longest single handler run and runs over budget, since the loop started. */
    var longestRunNanos = 0L
        private set
    var stalls = 0L
        private set
    var runs = 0L
        private set

    fun observe(to: HandlerSink) = sink.store(to)

    fun emit(observation: HandlerObservation) = sink.load().observed(observation)

    /** Any thread: a handler was placed on this loop. */
    fun activate() = mode.store(HandlerMode.Active.ordinal)

    fun mode(): HandlerMode = if (mode.load() == HandlerMode.Active.ordinal) HandlerMode.Active else HandlerMode.Idle

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (currentThreadId() == loopThread.load()) {
            when (life) {
                HandlerLife.Running -> ready.add(block, context)
                HandlerLife.Stopped -> Dispatchers.Default.dispatch(context, block)
            }
        } else {
            port.submit(Resume(this, block, context))
        }
    }

    /** Loop thread: the pass begins on this thread. */
    fun passBegins(thread: Long) {
        if (loopThread.load() != thread) loopThread.store(thread)
        rounds = 0
    }

    /** Loop thread: a serve of every connection begins. */
    fun serving() {
        held = 0
    }

    /** Loop thread: a connection held its ACK for the handler phase. */
    fun ackHeld() {
        held++
    }

    /** Loop thread: whether the pass runs another handler round: one is ready or an ACK waits, up to [MAX_ROUNDS]. */
    fun nextRound(): Round = if ((held > 0 || ready.size > 0) && rounds < MAX_ROUNDS) Round.Due else Round.Done

    /** Loop thread: handlers are still ready, or an ACK still waits, after the pass's rounds: the next pass is due now. */
    fun hasReady(): Boolean = ready.size > 0 || held > 0

    /** Loop thread: phase 2. Runs each continuation ready before this call until it completes or suspends. */
    fun runReady(clock: LoopClock) {
        rounds++
        val count = ready.size
        for (i in 0 until count) {
            val context = ready.peekContext()
            val block = ready.take()
            val began = clock.now().value
            try {
                block.run()
            } catch (failure: Exception) {
                emit(HandlerObservation.Failed(context[HandlerSite] ?: HandlerSite.Unnamed, HandlerFailure.Threw(failure)))
            }
            val heldFor = clock.now().value - began
            runs++
            if (heldFor > longestRunNanos) longestRunNanos = heldFor
            if (heldFor > stallBudgetNanos) {
                stalls++
                emit(HandlerObservation.Stalled(context[HandlerSite] ?: HandlerSite.Unnamed, heldFor, stallBudgetNanos))
            }
        }
    }

    /** Loop thread, once: what is still ready runs elsewhere, and so does every later resume. */
    fun stop() {
        life = HandlerLife.Stopped
        while (ready.size > 0) {
            val context = ready.peekContext()
            Dispatchers.Default.dispatch(context, ready.take())
        }
    }

    internal fun enqueue(
        block: Runnable,
        context: CoroutineContext,
    ) = when (life) {
        HandlerLife.Running -> ready.add(block, context)
        HandlerLife.Stopped -> Dispatchers.Default.dispatch(context, block)
    }

    override fun toString(): String = "LoopHandlers"

    private companion object {
        const val NO_THREAD = Long.MIN_VALUE
        const val MAX_ROUNDS = 8
        const val DEFAULT_STALL_BUDGET_NANOS = 1_000_000L
    }
}

/** A continuation resumed off the loop, posted to it. Allocated per cross-thread resume. */
private class Resume(
    private val handlers: LoopHandlers,
    private val block: Runnable,
    private val context: CoroutineContext,
) : LoopWork() {
    override fun run(port: LoopPort) = handlers.enqueue(block, context)

    override fun gone(end: ConnEnd) = Dispatchers.Default.dispatch(context, block)
}

/** A growable FIFO ring of continuations and their contexts; allocates only when it grows. */
private class ReadyRing {
    private var blocks = arrayOfNulls<Runnable>(INITIAL)
    private var contexts = arrayOfNulls<CoroutineContext>(INITIAL)
    private var head = 0
    var size = 0
        private set

    fun add(
        block: Runnable,
        context: CoroutineContext,
    ) {
        if (size == blocks.size) grow()
        val at = (head + size) and (blocks.size - 1)
        blocks[at] = block
        contexts[at] = context
        size++
    }

    fun peekContext(): CoroutineContext = contexts[head]!!

    fun take(): Runnable {
        val block = blocks[head]!!
        blocks[head] = null
        contexts[head] = null
        head = (head + 1) and (blocks.size - 1)
        size--
        return block
    }

    private fun grow() {
        val b = arrayOfNulls<Runnable>(blocks.size * 2)
        val c = arrayOfNulls<CoroutineContext>(blocks.size * 2)
        for (i in 0 until size) {
            b[i] = blocks[(head + i) and (blocks.size - 1)]
            c[i] = contexts[(head + i) and (blocks.size - 1)]
        }
        blocks = b
        contexts = c
        head = 0
    }

    private companion object {
        const val INITIAL = 64
    }
}
