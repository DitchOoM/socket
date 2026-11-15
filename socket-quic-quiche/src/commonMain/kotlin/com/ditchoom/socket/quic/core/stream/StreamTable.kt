package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.Conn

/** What a parked operation says when the connection retries it. */
internal sealed interface Retry {
    /** It finished: resumed its caller, or found its caller gone. */
    object Settled : Retry

    /** Still waiting for the stream. */
    object Again : Retry
}

/** A stream operation waiting for its stream to become readable or writable. Runs inside the connection. */
internal interface Parked {
    fun retry(
        inside: Conn.Inside,
        stream: StreamEntry,
    ): Retry

    /** The connection ended with the operation still parked. */
    fun connectionEnded(failure: Throwable)
}

internal sealed interface Waiter {
    object Nobody : Waiter

    class Waiting(
        val op: Parked,
    ) : Waiter
}

/** What the peer's side of a stream has told us so far. */
internal sealed interface Received {
    object Open : Received

    /** Every byte up to the peer's FIN was handed to a reader. */
    object Finished : Received
}

/** One stream's state. Confined to whoever is inside the connection. */
internal class StreamEntry(
    val id: QuicStreamId,
) {
    var reader: Waiter = Waiter.Nobody
    var writer: Waiter = Waiter.Nobody
    var received: Received = Received.Open

    /** Retries a parked reader; it stays parked while it answers [Retry.Again]. */
    fun wakeReader(inside: Conn.Inside) {
        when (val now = reader) {
            Waiter.Nobody -> Unit
            is Waiter.Waiting -> if (now.op.retry(inside, this) === Retry.Settled && reader === now) reader = Waiter.Nobody
        }
    }

    fun wakeWriter(inside: Conn.Inside) {
        when (val now = writer) {
            Waiter.Nobody -> Unit
            is Waiter.Waiting -> if (now.op.retry(inside, this) === Retry.Settled && writer === now) writer = Waiter.Nobody
        }
    }

    fun endWaiters(failure: Throwable) {
        val waiting = listOf(reader, writer)
        reader = Waiter.Nobody
        writer = Waiter.Nobody
        for (w in waiting) if (w is Waiter.Waiting) w.op.connectionEnded(failure)
    }
}

/** A connection's streams by id. Confined to whoever is inside the connection. */
internal class StreamMap {
    private val entries = HashMap<Long, StreamEntry>()

    fun contains(id: QuicStreamId): Boolean = entries.containsKey(id.id)

    fun entry(id: QuicStreamId): StreamEntry = entries.getOrPut(id.id) { StreamEntry(id) }

    fun forEach(visit: (StreamEntry) -> Unit) = entries.values.forEach(visit)
}
