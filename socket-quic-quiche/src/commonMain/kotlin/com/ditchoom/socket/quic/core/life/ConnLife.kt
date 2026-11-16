@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A connection's life, as any thread may read it. */
internal sealed interface Life {
    data object Open : Life

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
    private val life = AtomicReference<Life>(Life.Open)

    fun view(): Life = life.load()

    fun observe(): LifeStep = if (q.api.connIsClosed(q.handle)) LifeStep.Closed else LifeStep.Open

    /** How quiche's closed connection ended: timed out, closed by the peer, by us, or with nothing recorded. */
    fun quicheEnd(): ConnEnd {
        if (q.api.connIsTimedOut(q.handle)) return ConnEnd.IdleTimeout
        val peer = q.api.connPeerError(q.handle)
        if (peer != null) return ConnEnd.ByPeer(peer)
        val local = q.api.connLocalError(q.handle)
        if (local != null) return ConnEnd.ByLocal(local)
        return ConnEnd.NoErrorRecorded
    }

    /** Starts quiche's close with [error]; the end is recorded when quiche reports the connection closed. */
    fun close(error: QuicError) {
        q.api.connClose(q.handle, error)
    }

    /** Records [end], once. */
    fun end(end: ConnEnd): Ending = if (life.compareAndSet(Life.Open, Life.Ended(end))) Ending.First else Ending.Already
}
