package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheStreamIter
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.LongObjectMap
import com.ditchoom.socket.quic.core.pool.LongQueue
import com.ditchoom.socket.quic.core.pool.LongSet

/** Whether the table's connection has ended, and how: an operation that starts after the end fails with it. */
internal sealed interface Streams {
    data object Open : Streams

    class Ended(
        val end: ConnEnd,
    ) : Streams
}

/** What [StreamTable.tryOpen] did. */
internal enum class Opened { Opened, AtLimit }

/**
 * A connection's streams: entries by id, operations waiting to write, opens waiting for the peer's stream credit, and
 * the streams the peer opened that nobody has been told of yet. Inside the connection only.
 */
@Confined
internal class StreamTable(
    private val q: QuicheConnRef,
    val io: StreamIo,
    private val role: QuicRole,
) {
    private val entries = LongObjectMap<StreamEntry>()

    /** The buffer a read that ended without handing one to its caller leaves for the next read. */
    val idle = IdleBuffer()

    /** Made once: it captures nothing, so a lookup of a stream that exists allocates nothing. */
    private val newEntry: (Long) -> StreamEntry = { id -> StreamEntry(QuicStreamId(id)) }
    private val writeWaiters = LongSet()
    private val opens = ArrayDeque<Waiter>()
    private val incoming = LongQueue()
    private var nextBidi: Long = if (role == QuicRole.Client) 0L else 1L
    private var opened: Long = 0L

    var state: Streams = Streams.Open
        private set

    fun entry(id: QuicStreamId): StreamEntry = entries.getOrPut(id.id, newEntry)

    /** Asks for [stream]'s writer to be retried once quiche says it can take bytes. */
    fun wantWritable(stream: QuicStreamId) {
        writeWaiters.add(stream.id)
    }

    /** Queues [open] behind the opens already waiting for the peer's credit. */
    fun waitToOpen(open: Waiter) {
        opens.addLast(open)
    }

    fun withdrawOpen(open: Waiter) {
        opens.remove(open)
    }

    fun opensWaiting(): Int = opens.size

    /**
     * Opens the next bidirectional stream: a zero-length send makes quiche create it (a read on a stream quiche has
     * not created answers INVALID_STREAM_STATE), which is also where the peer's limit shows. At the limit the id is
     * not spent.
     */
    fun tryOpen(): Opened {
        val id = QuicStreamId(nextBidi)
        if (io.send(id, 0L, 0, WriteEnd.More).result == QUICHE_ERR_STREAM_LIMIT) return Opened.AtLimit
        nextBidi += STREAM_ID_STEP
        entry(id)
        opened = id.id
        return Opened.Opened
    }

    /** The stream the last [tryOpen] that answered [Opened.Opened] created. */
    fun lastOpened(): QuicStreamId = QuicStreamId(opened)

    /** Retries what quiche's readable and writable streams, and the peer's stream credit, let go on. */
    fun wake() {
        val readable = q.api.connReadable(q.handle)
        if (!readable.isExhausted) wakeReaders(readable)
        if (writeWaiters.isNotEmpty()) wakeWriters()
        while (opens.isNotEmpty() && q.api.connPeerStreamsLeftBidi(q.handle) > 0) {
            if (opens.first().retry(this) == Retry.Again) return
            opens.removeFirst()
        }
    }

    private fun wakeReaders(readable: QuicheStreamIter) {
        try {
            while (q.api.streamIterNextId(readable).fold(end = { false }, stream = { readable(it) })) Unit
        } finally {
            q.api.streamIterFree(readable)
        }
    }

    /** The oldest stream the peer opened that has not been handed up yet. */
    inline fun takeIncoming(each: (QuicStreamId) -> Unit) = incomingIds().drain { each(QuicStreamId(it)) }

    @PublishedApi
    internal fun incomingIds(): LongQueue = incoming

    /** The connection ended with [end]: every waiting operation fails with it, and so does every later one. */
    fun endAll(end: ConnEnd) {
        if (state is Streams.Ended) return
        state = Streams.Ended(end)
        idle.free()
        entries.forEachValue { it.endWaiters(end) }
        while (opens.isNotEmpty()) opens.removeFirst().connectionEnded(end)
    }

    private fun readable(id: QuicStreamId): Boolean {
        if (!entries.containsKey(id.id) && initiatedByPeer(id)) {
            entry(id)
            incoming.addLast(id.id)
        }
        entry(id).wakeReader(this)
        return true
    }

    private fun wakeWriters() {
        val writable = q.api.connWritable(q.handle)
        if (writable.isExhausted) return
        try {
            while (q.api.streamIterNextId(writable).fold(end = { false }, stream = { writable(it) })) Unit
        } finally {
            q.api.streamIterFree(writable)
        }
    }

    private fun writable(id: QuicStreamId): Boolean {
        if (writeWaiters.remove(id.id)) entry(id).wakeWriter(this)
        return true
    }

    private fun initiatedByPeer(id: QuicStreamId): Boolean = (id.id and 1L == 1L) == (role == QuicRole.Client)

    private companion object {
        const val STREAM_ID_STEP = 4L
    }
}
