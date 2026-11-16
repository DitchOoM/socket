@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A connection's life, as any thread may read it. */
internal sealed interface Life {
    /** Not ended yet. */
    sealed interface Open : Life

    /** Open, and nobody closed it, or quiche declined our close because it was already closing. */
    data object Running : Open

    /** Open, draining a close quiche accepted from us before it had read any close from the peer: it ends by us. */
    class ClosingByUs(
        val error: QuicError,
    ) : Open

    class Ended(
        val end: ConnEnd,
    ) : Life
}

/** What quiche says this turn: still open, or closed (its end is [ConnLife.quicheEnd]). */
internal enum class LifeStep { Open, Closed }

/** Whether this call recorded the end, or another had already. */
internal enum class Ending { First, Already }

/**
 * Lifecycle and the connection's end. [end] is the only writer of the end; quiche's own close is read here and nowhere
 * else, so how a connection ended has one decider.
 */
@Published
internal class ConnLife(
    private val q: QuicheConnRef,
) {
    private val life = AtomicReference<Life>(Life.Running)

    fun view(): Life = life.load()

    fun observe(): LifeStep = if (q.api.connIsClosed(q.handle)) LifeStep.Closed else LifeStep.Open

    /**
     * How quiche's closed connection ended: by our close if quiche accepted it first (a peer's close that crosses ours
     * records a peer error too), else timed out, closed by the peer, by us, or with nothing recorded.
     */
    fun quicheEnd(): ConnEnd {
        when (val now = life.load()) {
            is Life.ClosingByUs -> return ConnEnd.ByLocal(now.error)
            is Life.Running, is Life.Ended -> Unit
        }
        if (q.api.connIsTimedOut(q.handle)) return ConnEnd.IdleTimeout
        val peer = q.api.connPeerError(q.handle)
        if (peer != null) return ConnEnd.ByPeer(peer)
        val local = q.api.connLocalError(q.handle) ?: return ConnEnd.NoErrorRecorded
        // quiche closes with NO_VIABLE_PATH when it has no path left to send on, and sends nothing.
        if (local is QuicError.NoViablePath) return ConnEnd.NoUsablePath
        return ConnEnd.ByLocal(local)
    }

    /** Starts quiche's close with [error]; the end is recorded when quiche reports the connection closed. */
    fun close(error: QuicError) {
        if (q.api.connClose(q.handle, error) == QUICHE_OK) life.compareAndSet(Life.Running, Life.ClosingByUs(error))
    }

    /** Records [end], once. */
    fun end(end: ConnEnd): Ending {
        while (true) {
            when (val seen = life.load()) {
                is Life.Ended -> return Ending.Already
                is Life.Open -> if (life.compareAndSet(seen, Life.Ended(end))) return Ending.First
            }
        }
    }
}

private const val QUICHE_OK = 0
