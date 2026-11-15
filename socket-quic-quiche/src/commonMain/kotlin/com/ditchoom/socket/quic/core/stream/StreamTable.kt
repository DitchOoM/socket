package com.ditchoom.socket.quic.core.stream

import androidx.collection.MutableLongObjectMap
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

/** The slot of a stream that nothing waits on. */
internal object Nobody : Parked {
    override fun retry(
        inside: Conn.Inside,
        stream: StreamEntry,
    ): Retry = Retry.Settled

    override fun connectionEnded(failure: Throwable) = Unit
}

/** What the peer's side of a stream has told us so far. */
internal sealed interface Received {
    object Open : Received

    /** Every byte up to the peer's FIN was handed to a reader. */
    object Finished : Received
}

/**
 * How many bytes a stream's next read asks quiche for, which is the size of the buffer the read allocates: it starts at
 * [Initial], doubles up to [Max] while reads fill it and halves down to [Initial] while they use a quarter or less of
 * it, so a stream of small messages holds small buffers and a bulk stream converges on [Max].
 */
@kotlin.jvm.JvmInline
internal value class ReadChunk private constructor(
    val bytes: Int,
) {
    /** The chunk for the read after one that [asked] for that many bytes and got [read] of them. */
    fun after(
        asked: Int,
        read: Int,
    ): ReadChunk =
        when {
            read >= asked -> ReadChunk(minOf(asked * 2, MAX_BYTES))
            read * SHRINK_BELOW <= asked -> ReadChunk(maxOf(asked / 2, INITIAL_BYTES))
            else -> this
        }

    companion object {
        private const val INITIAL_BYTES = 2_048
        const val MAX_BYTES = 65_536
        private const val SHRINK_BELOW = 4

        val Initial = ReadChunk(INITIAL_BYTES)
        val Max = ReadChunk(MAX_BYTES)
    }
}

/** One stream's state. Confined to whoever is inside the connection. */
internal class StreamEntry(
    val id: QuicStreamId,
) {
    var reader: Parked = Nobody
    var writer: Parked = Nobody
    var received: Received = Received.Open
    var chunk: ReadChunk = ReadChunk.Initial

    /** Retries a parked reader; it stays parked while it answers [Retry.Again]. */
    fun wakeReader(inside: Conn.Inside) {
        val now = reader
        if (now.retry(inside, this) === Retry.Settled && reader === now) reader = Nobody
    }

    fun wakeWriter(inside: Conn.Inside) {
        val now = writer
        if (now.retry(inside, this) === Retry.Settled && writer === now) writer = Nobody
    }

    fun endWaiters(failure: Throwable) {
        val waitingReader = reader
        val waitingWriter = writer
        reader = Nobody
        writer = Nobody
        waitingReader.connectionEnded(failure)
        waitingWriter.connectionEnded(failure)
    }
}

/** A connection's streams by id. Confined to whoever is inside the connection. */
internal class StreamMap {
    private val entries = MutableLongObjectMap<StreamEntry>()

    fun contains(id: QuicStreamId): Boolean = entries.containsKey(id.id)

    fun entry(id: QuicStreamId): StreamEntry = entries.getOrPut(id.id) { StreamEntry(id) }

    fun forEach(visit: (StreamEntry) -> Unit) = entries.forEachValue(visit)
}
