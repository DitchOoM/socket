@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.time.Nanos
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A connection's life, as any thread may read it: [Running] → [Closing] → [Lingering] → [Ended], or straight to
 * [Lingering] or [Ended] from either open state. Callers may use it while it is [Open]; once it is [Over] they have its
 * end. What it still does on the wire is its [Linger]'s.
 */
internal sealed interface Life {
    sealed interface Open : Life

    data object Running : Open

    /** A close was asked; the connection ends with [end] once its CONNECTION_CLOSE is out, or quiche ends it first. */
    class Closing(
        val end: ConnEnd,
    ) : Open

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

/** How a connection that is over ended. */
internal fun endOf(over: Life.Over): ConnEnd =
    when (over) {
        is Life.Lingering -> over.end
        is Life.Ended -> over.end
    }

/** What quiche says this turn: still open, or closed (its end is [ConnLife.quicheEnd]). */
internal enum class LifeStep { Open, Closed }

/** Whether this call recorded the end, or another had already. */
internal enum class Ending { First, Already }

/**
 * Lifecycle and the connection's end. [end] is the only writer of the end; quiche's own close is read here and nowhere
 * else, so how a connection ended has one decider. Owns the connection's [linger]. Written inside the connection only.
 */
@Published
internal class ConnLife(
    private val q: QuicheConnRef,
    val linger: Linger,
) {
    private val life = AtomicReference<Life>(Life.Running)

    fun view(): Life = life.load()

    fun observe(): LifeStep = if (q.api.connIsClosed(q.handle)) LifeStep.Closed else LifeStep.Open

    /** Whether quiche, holding our close, has sent its CONNECTION_CLOSE: it drains and sends nothing more. */
    fun closeSent(): Boolean = q.api.connIsDraining(q.handle)

    /**
     * How quiche's closed connection ended: as the close asked of it said, unless quiche timed out first; else timed
     * out, closed by the peer, by us, or with nothing recorded.
     */
    fun quicheEnd(): ConnEnd {
        val timedOut = q.api.connIsTimedOut(q.handle)
        when (val now = life.load()) {
            is Life.Closing -> return if (timedOut) ConnEnd.IdleTimeout else now.end
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
     * A close asked at [now] with [error], ending the connection with [end] once its CONNECTION_CLOSE is out. A
     * graceful one flushes first as the [CloseRule] says. Asked again with [CloseHow.Now] while flushing, it sends
     * [error] at once. A connection whose peer closed first ends as quiche says, and sends nothing.
     */
    fun close(
        error: QuicError,
        end: ConnEnd,
        how: CloseHow,
        now: Nanos,
    ) {
        when (life.load()) {
            is Life.Running -> {
                if (observe() == LifeStep.Closed || q.api.connPeerError(q.handle) != null) {
                    life.store(Life.Closing(quicheEnd()))
                    linger.draining()
                    return
                }
                life.store(Life.Closing(end))
                when (linger.begin(error, how, now)) {
                    Begun.Flushing -> Unit
                    Begun.SendNow -> send(error)
                }
            }
            is Life.Closing ->
                when (linger.wire()) {
                    is Wire.Flushing -> if (how == CloseHow.Now) send(error)
                    is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Answering, is Wire.Silent, is Wire.Done -> Unit
                }
            is Life.Over -> Unit
        }
    }

    /** Each serve while closing: a flush that is over at [now] hands quiche the close. */
    fun flushStep(now: Nanos) {
        when (val w = linger.wire()) {
            is Wire.Flushing -> if (linger.flushOver(w, now)) send(w.error)
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Answering, is Wire.Silent, is Wire.Done -> Unit
        }
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

    private fun send(error: QuicError) {
        if (q.api.connClose(q.handle, error) == QUICHE_OK) linger.handedToQuiche() else linger.draining()
    }
}

private const val QUICHE_OK = 0
