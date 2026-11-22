package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined

/** A connection's [OpenLane] of each kind. Inside the connection only. */
@Confined
internal class OpenLanes(
    q: QuicheConnRef,
    role: QuicRole,
) {
    private val bidirectional = OpenLane(q, StreamKind.Bidirectional, role)
    private val unidirectional = OpenLane(q, StreamKind.Unidirectional, role)

    fun lane(kind: StreamKind): OpenLane =
        when (kind) {
            StreamKind.Bidirectional -> bidirectional
            StreamKind.Unidirectional -> unidirectional
        }

    fun waits(): Int = bidirectional.waits() + unidirectional.waits()

    fun wake(table: StreamTable) {
        bidirectional.wake(table)
        unidirectional.wake(table)
    }

    fun refuseAll(table: StreamTable) {
        bidirectional.refuseAll(table)
        unidirectional.refuseAll(table)
    }

    fun endAll(end: ConnEnd) {
        bidirectional.endAll(end)
        unidirectional.endAll(end)
    }
}

/**
 * The opens of one [kind] of stream: the next id this side opens of it, and the opens waiting, oldest first, for the
 * peer's credit of that kind. An open of one kind never waits behind the other's credit. Inside the connection only.
 */
@Confined
internal class OpenLane(
    private val q: QuicheConnRef,
    val kind: StreamKind,
    role: QuicRole,
) {
    /** The id the next open of this kind takes; every id under it was opened. */
    var next: Long = kind.firstLocal(role)
        private set
    private val waiting = ArrayDeque<Waiter>()

    fun waits(): Int = waiting.size

    fun wait(open: Waiter) = waiting.addLast(open)

    fun withdraw(open: Waiter) {
        waiting.remove(open)
    }

    /** The open at [next] was made: the id is spent. */
    fun spend(): QuicStreamId {
        val id = QuicStreamId(next)
        next += STREAM_ID_STEP
        return id
    }

    /** Retries the waiting opens, oldest first, while the peer grants streams of this kind. */
    fun wake(table: StreamTable) {
        while (waiting.isNotEmpty() && left() > 0) {
            if (waiting.first().retry(table) == Retry.Again) return
            waiting.removeFirst()
        }
    }

    /**
     * At the close point: every waiting open is retried once, which refuses it and takes it out of the lane; one that
     * still answers Again keeps its place, to be told the end.
     */
    fun refuseAll(table: StreamTable) =
        repeat(waiting.size) { waiting.removeFirst().let { if (it.retry(table) == Retry.Again) waiting.addLast(it) } }

    fun endAll(end: ConnEnd) {
        while (waiting.isNotEmpty()) waiting.removeFirst().connectionEnded(end)
    }

    /** How many more streams of this kind the peer lets this side open now. */
    private fun left(): Long =
        when (kind) {
            StreamKind.Bidirectional -> q.api.connPeerStreamsLeftBidi(q.handle)
            StreamKind.Unidirectional -> q.api.connPeerStreamsLeftUni(q.handle)
        }

    private companion object {
        const val STREAM_ID_STEP = 4L
    }
}
