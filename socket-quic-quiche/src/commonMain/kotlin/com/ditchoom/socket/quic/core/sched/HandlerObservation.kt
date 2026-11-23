package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Which handler code a step of a lane belongs to, carried in the coroutine's context so a report can name it. */
internal sealed class HandlerSite : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HandlerSite>

    /** The handler of the peer's stream [stream]. */
    class Stream(
        val stream: QuicStreamId,
    ) : HandlerSite()

    /** The connection's acceptor, which takes each stream the peer opens and starts its handler. */
    data object Accepting : HandlerSite()

    /** A server's offer of an established connection to whoever accepts it. */
    data object Offering : HandlerSite()

    /** A step whose coroutine carries no site. */
    data object Unnamed : HandlerSite()

    /** What a worker's current step context is while it runs no step; never a step's own. */
    data object NoStep : HandlerSite()
}

/** What a handler pool saw, on the pool's observation stream. */
internal sealed interface HandlerObservation {
    /**
     * One step of [site] on [connection] had held its pool thread for [heldNanos], over the pool's bound of [boundNanos],
     * when it was seen: by the loop's watch while it still ran, or by its thread when it returned. Reported once a step.
     */
    class Stalled(
        val connection: QuicSessionId,
        val site: HandlerSite,
        val heldNanos: Long,
        val boundNanos: Long,
    ) : HandlerObservation

    /** The handler of [site] on [connection] threw [thrown]; it is reported before its stream is reset. */
    class Failed(
        val connection: QuicSessionId,
        val site: HandlerSite,
        val thrown: Thrown,
    ) : HandlerObservation

    /** A step of [site] on [connection] threw [thrown] out of the coroutine machinery; the pool thread went on. */
    class StepThrew(
        val connection: QuicSessionId,
        val site: HandlerSite,
        val thrown: Thrown,
    ) : HandlerObservation

    /** The acceptor of [connection] failed to take a stream with [thrown], its connection still open; it goes on. */
    class AcceptFailed(
        val connection: QuicSessionId,
        val thrown: Thrown,
    ) : HandlerObservation

    /** Thread [index] of the pool could not start: [thrown]. The pool runs on the threads that did. */
    class ThreadNotStarted(
        val index: Int,
        val thrown: Thrown,
    ) : HandlerObservation
}

/** The site of the coroutine [context] belongs to. */
internal fun siteOf(context: CoroutineContext): HandlerSite = context[HandlerSite] ?: HandlerSite.Unnamed
