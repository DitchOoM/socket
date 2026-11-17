package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined

/** What the peer's side of a stream has told us so far. */
internal enum class Received {
    Open,

    /** Every byte up to the peer's FIN was handed to a reader. */
    Finished,
}

/** What our side of a stream has sent so far. */
internal enum class Sent {
    Open,

    /** Our FIN went to quiche. */
    Finished,
}

/**
 * One stream: its parked reader and writer, how far each side got, and its read size. Reused for a later stream once
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
    var received: Received = Received.Open
        private set
    var chunk: ReadChunk = ReadChunk.Initial
        private set
    var sent: Sent = Sent.Open
        private set

    /** The entry, at rest, becomes [stream]'s. */
    fun reuse(stream: QuicStreamId): StreamEntry {
        id = stream
        reader = Nobody
        writer = Nobody
        received = Received.Open
        chunk = ReadChunk.Initial
        sent = Sent.Open
        return this
    }

    fun finSent() {
        sent = Sent.Finished
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

    fun finished() {
        received = Received.Finished
    }

    /** A read that [asked] for that many bytes got [got]: the next read's size follows [ReadChunk.after]. */
    fun read(
        asked: Int,
        got: Int,
    ) {
        chunk = chunk.after(asked, got)
    }

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
