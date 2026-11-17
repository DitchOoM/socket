package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined

/** Where one direction of a stream stands: still open, or ended, and how. The first ending stays. */
internal sealed interface Side {
    data object Open : Side

    /** Ended: nothing more moves this way. */
    sealed interface Ended : Side

    /** Receiving: every byte up to the peer's FIN was read. Sending: our FIN went to quiche. */
    data object Finished : Ended

    /** Receiving: the peer reset its side. Sending: we reset ours. */
    data object Reset : Ended

    /** Receiving: we told the peer to stop sending. Sending: the peer told us to. */
    data object Stopped : Ended

    /** Receiving only: quiche refused a read of a stream nobody reads; nothing more arrives. */
    data object Refused : Ended

    /**
     * Receiving only: the caller closed without reading to the end. What still arrives is read and dropped, so the
     * peer's writes go on and its credit comes back, until the peer's FIN or reset ends this direction.
     */
    data object Abandoned : Side
}

/**
 * One stream: its parked reader and writer, and how far each side got. Reused for a later stream once
 * this one is spent, so a new stream allocates no entry.
 */
@Confined
internal class StreamEntry(
    id: QuicStreamId,
) {
    var id: QuicStreamId = id
        private set
    var reader: Waiter = Nobody
        private set
    var writer: Waiter = Nobody
        private set
    var received: Side = Side.Open
        private set
    var sent: Side = Side.Open
        private set

    /** The entry, at rest, becomes [stream]'s. */
    fun reuse(stream: QuicStreamId): StreamEntry {
        id = stream
        reader = Nobody
        writer = Nobody
        received = Side.Open
        sent = Side.Open
        return this
    }

    /** Our side ended [how]; an earlier ending stays. */
    fun sendEnded(how: Side.Ended) {
        if (sent is Side.Open) sent = how
    }

    /** The peer's side ended [how] for us; an earlier ending stays. */
    fun receiveEnded(how: Side.Ended) {
        if (received !is Side.Ended) received = how
    }

    /** The caller will read no more; an ended direction stays ended. */
    fun abandonReceive() {
        if (received is Side.Open) received = Side.Abandoned
    }

    fun parkReader(waiter: Waiter) {
        reader = waiter
    }

    fun parkWriter(waiter: Waiter) {
        writer = waiter
    }

    /** Clears [waiter] from whichever slot holds it. */
    fun unpark(waiter: Waiter) {
        if (reader === waiter) reader = Nobody
        if (writer === waiter) writer = Nobody
    }

    fun finished() = receiveEnded(Side.Finished)

    /** Retries a parked reader; it stays parked while it answers [Retry.Again]. */
    fun wakeReader(table: StreamTable) {
        val now = reader
        if (now.retry(table) == Retry.Settled && reader === now) reader = Nobody
        table.collect(this)
    }

    fun wakeWriter(table: StreamTable) {
        val now = writer
        if (now.retry(table) == Retry.Settled && writer === now) writer = Nobody
        table.collect(this)
    }

    fun endWaiters(end: ConnEnd) {
        val waitingReader = reader
        val waitingWriter = writer
        reader = Nobody
        writer = Nobody
        waitingReader.connectionEnded(end)
        waitingWriter.connectionEnded(end)
    }
}
