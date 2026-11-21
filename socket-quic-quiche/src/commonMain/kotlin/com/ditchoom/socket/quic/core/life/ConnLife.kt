@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A connection's life, as any thread may read it: [Running] → [Closing] → [Lingering] → [Ended], or straight to
 * [Lingering] or [Ended] from either open state. Only a [Running] connection accepts stream data: a [Closing] one is
 * past its close point. Callers may use it while it is [Open]; once it is [Over] they have its end. What it still does
 * on the wire is its [Linger]'s.
 */
internal sealed interface Life {
    sealed interface Open : Life

    data object Running : Open

    /** Past its close point; it ends with its end ([endOf]) once its CONNECTION_CLOSE is out, or quiche ends it first. */
    sealed interface Closing : Open

    /** A graceful close delivers what was written before its point, as [waits] says, until [until] at the latest. */
    class Flushing(
        val end: ConnEnd,
        val until: Nanos,
        val waits: DrainUntil,
    ) : Closing

    /** The CONNECTION_CLOSE is quiche's to send, or quiche is closing on its own; [outcome] is how the close went. */
    class Handed(
        val end: ConnEnd,
        val outcome: CloseOutcome,
    ) : Closing

    sealed interface Over : Life

    /** Callers have [end]; the connection still holds its ids, to send its close or answer late packets with it. */
    class Lingering(
        val end: ConnEnd,
    ) : Over

    /** Nothing is left on the wire: its ids, socket and memory may go. */
    class Ended(
        val end: ConnEnd,
    ) : Over
}

/** How a close asked of a connection went. */
internal sealed interface CloseOutcome {
    /** Everything written before the close point was delivered as the graceful close waited for; then it closed. */
    data object Drained : CloseOutcome

    /** The graceful close's limit passed first: it closed then, and what was still undelivered is abandoned. */
    data object LimitReached : CloseOutcome

    /** It closed at once: an immediate close, or one that cut a graceful one short. */
    data object Immediate : CloseOutcome

    /** The connection ended, or was ending, another way before its close went: [end] says how. */
    class EndedFirst(
        val end: ConnEnd,
    ) : CloseOutcome
}

/** How a connection that is over ended. */
internal fun endOf(over: Life.Over): ConnEnd =
    when (over) {
        is Life.Lingering -> over.end
        is Life.Ended -> over.end
    }

/** How a connection past its close point ends. */
internal fun endOf(closing: Life.Closing): ConnEnd =
    when (closing) {
        is Life.Flushing -> closing.end
        is Life.Handed -> closing.end
    }

/**
 * What quiche says this turn: still open; draining with no close of ours to send, the peer's CONNECTION_CLOSE having
 * arrived (or quiche's own close gone out); or closed.
 */
internal enum class LifeStep { Open, Draining, Closed }

/** Whether this call recorded the end, or another had already. */
internal enum class Ending { First, Already }

/** Where a graceful close's flush stands at a serve. */
private enum class Flush { Waiting, Drained, LimitReached }

/**
 * Lifecycle and the connection's end. [end] is the only writer of the end; quiche's own close is read here and nowhere
 * else, so how a connection ended has one decider. Owns the connection's [linger]; [noError] is what a close with
 * NO_ERROR and no choice of its own asks for. Written inside the connection only.
 */
@Published
internal class ConnLife(
    private val q: QuicheConnRef,
    val linger: Linger,
    val noError: CloseChoice,
) {
    private val life = AtomicReference<Life>(Life.Running)

    fun view(): Life = life.load()

    /** A datagram reached quiche: only one can bring the peer's CONNECTION_CLOSE. */
    fun heard() = linger.heard()

    /**
     * Each serve: closed, or draining with no close of ours to send once the peer's CONNECTION_CLOSE arrived, which is
     * asked of quiche only after a datagram reached it.
     */
    fun observe(): LifeStep {
        if (q.api.connIsClosed(q.handle)) return LifeStep.Closed
        return when {
            linger.takeHeard() == Heard.Nothing || linger.sending() -> LifeStep.Open
            q.api.connIsDraining(q.handle) -> LifeStep.Draining
            else -> LifeStep.Open
        }
    }

    /** Whether quiche, holding our close, has sent its CONNECTION_CLOSE: it drains and sends nothing more. */
    fun closeSent(): Boolean = q.api.connIsDraining(q.handle)

    /**
     * How quiche's closed connection ended: as the close asked of it said, unless quiche timed out first; else timed
     * out, closed by the peer, by us, or with nothing recorded.
     */
    fun quicheEnd(): ConnEnd {
        val timedOut = q.api.connIsTimedOut(q.handle)
        when (val now = life.load()) {
            is Life.Closing -> return if (timedOut) ConnEnd.IdleTimeout else endOf(now)
            is Life.Running, is Life.Over -> Unit
        }
        if (timedOut) return ConnEnd.IdleTimeout
        val peer = q.api.connPeerError(q.handle)
        if (peer != null) return ConnEnd.ByPeer(peer)
        val local = q.api.connLocalError(q.handle) ?: return ConnEnd.NoErrorRecorded
        // quiche closes with NO_VIABLE_PATH when it has no path left to send on, and sends nothing.
        if (local is QuicError.NoViablePath) return ConnEnd.NoUsablePath
        return ConnEnd.ByLocal(local)
    }

    /**
     * The close point, at [now]: the connection ends with [end] once its CONNECTION_CLOSE is out. A graceful [choice]
     * flushes first; an immediate one asked while flushing cuts the flush short. A connection whose peer closed first
     * ends as quiche says, and sends nothing.
     */
    fun close(
        choice: CloseChoice,
        end: ConnEnd,
        now: Nanos,
    ) {
        when (val at = life.load()) {
            is Life.Running -> {
                if (q.api.connIsClosed(q.handle) || q.api.connIsDraining(q.handle)) {
                    val ending = quicheEnd()
                    life.store(Life.Handed(ending, CloseOutcome.EndedFirst(ending)))
                    linger.draining()
                    return
                }
                when (choice) {
                    is CloseChoice.Graceful -> life.store(Life.Flushing(end, now + choice.limitNanos, choice.waits))
                    is CloseChoice.Immediate -> hand(Life.Handed(end, CloseOutcome.Immediate), choice.error)
                }
            }
            is Life.Flushing ->
                when (choice) {
                    is CloseChoice.Immediate -> hand(Life.Handed(at.end, CloseOutcome.Immediate), choice.error)
                    is CloseChoice.Graceful -> Unit
                }
            is Life.Handed, is Life.Over -> Unit
        }
    }

    /** Each serve while closing: a flush that is over at [now] hands quiche the close. */
    fun flushStep(now: Nanos) {
        val flushing =
            when (val at = life.load()) {
                is Life.Flushing -> at
                is Life.Running, is Life.Handed, is Life.Over -> return
            }
        when (flushed(flushing, now)) {
            Flush.Waiting -> Unit
            Flush.Drained -> hand(Life.Handed(flushing.end, CloseOutcome.Drained), QuicError.NoError)
            Flush.LimitReached -> hand(Life.Handed(flushing.end, CloseOutcome.LimitReached), QuicError.NoError)
        }
    }

    private fun flushed(
        flushing: Life.Flushing,
        now: Nanos,
    ): Flush {
        val delivered =
            when (flushing.waits) {
                DrainUntil.Acknowledged -> !q.api.connStreamDataUnacknowledged(q.handle)
                DrainUntil.PeerDone -> false
            }
        return when {
            delivered -> Flush.Drained
            now >= flushing.until -> Flush.LimitReached
            else -> Flush.Waiting
        }
    }

    /** The connection ends another way while flushing, with quiche still open: its CONNECTION_CLOSE goes now. */
    fun endFlush() {
        when (val at = life.load()) {
            is Life.Flushing ->
                if (observe() ==
                    LifeStep.Open
                ) {
                    hand(Life.Handed(at.end, CloseOutcome.EndedFirst(at.end)), QuicError.NoError)
                }
            is Life.Running, is Life.Handed, is Life.Over -> Unit
        }
    }

    /** quiche drains with no close of ours: nothing of ours goes out, and the connection keeps its ids until [until]. */
    fun drains(until: Nanos) = linger.silent(until)

    /** How the close asked of this connection went, now that it ends with [end]; read before [end] records it. */
    fun outcome(end: ConnEnd): CloseOutcome =
        when (val at = life.load()) {
            is Life.Handed -> at.outcome
            is Life.Flushing ->
                when (at.waits) {
                    DrainUntil.PeerDone -> if (q.api.connPeerError(q.handle) != null) CloseOutcome.Drained else CloseOutcome.EndedFirst(end)
                    DrainUntil.Acknowledged -> CloseOutcome.EndedFirst(end)
                }
            is Life.Running, is Life.Over -> CloseOutcome.EndedFirst(end)
        }

    /** Offers the time the close next needs the connection served: a flush's limit, or the linger's end. */
    fun offerWake(sink: WakeSink) {
        when (val at = life.load()) {
            is Life.Flushing -> sink.offer(at.until)
            is Life.Running, is Life.Handed, is Life.Over -> Unit
        }
        linger.offerWake(sink)
    }

    /** Records [end], once. */
    fun end(end: ConnEnd): Ending {
        when (life.load()) {
            is Life.Over -> return Ending.Already
            is Life.Open -> Unit
        }
        val over =
            when (linger.afterEnd()) {
                AfterEnd.Lingers -> Life.Lingering(end)
                AfterEnd.Released -> Life.Ended(end)
            }
        life.store(over)
        return Ending.First
    }

    /** The linger is over: nothing is left on the wire. */
    fun settle() {
        linger.done()
        when (val now = life.load()) {
            is Life.Lingering -> life.store(Life.Ended(now.end))
            is Life.Running, is Life.Closing, is Life.Ended -> Unit
        }
    }

    private fun hand(
        handed: Life.Handed,
        error: QuicError,
    ) {
        life.store(handed)
        if (q.api.connClose(q.handle, error) == QUICHE_OK) linger.handedToQuiche() else linger.draining()
    }
}

private const val QUICHE_OK = 0
