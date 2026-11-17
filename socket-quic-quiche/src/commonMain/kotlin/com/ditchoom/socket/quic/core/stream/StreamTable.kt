package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
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

    /** Entries of spent streams, kept for the next streams. */
    private val spare = ArrayList<StreamEntry>()

    /** What [unpark] finds for a stream that has no entry: nothing waits on it. */
    private val absent = StreamEntry(QuicStreamId(0L))

    /** Made once, so a lookup of a stream that exists allocates nothing, and a new stream reuses a spent one's entry. */
    private val newEntry: (Long) -> StreamEntry = { id ->
        if (spare.isEmpty()) StreamEntry(QuicStreamId(id)) else spare.removeAt(spare.size - 1).reuse(QuicStreamId(id))
    }
    private val writeWaiters = LongSet()
    private val opens = ArrayDeque<Waiter>()
    private val incoming = LongQueue()
    private var nextBidi: Long = if (role == QuicRole.Client) 0L else 1L
    private var opened: Long = 0L

    var state: Streams = Streams.Open
        private set

    fun entry(id: QuicStreamId): StreamEntry = entries.getOrPut(id.id, newEntry)

    /** Whether [id] names a stream that is spent: one this side opened or was told of, whose entry was let go. */
    fun isSpent(id: QuicStreamId): Boolean = !entries.containsKey(id.id) && (initiatedByPeer(id) || id.id < nextBidi)

    /** Clears [waiter] from [id]'s entry, if the stream still has one. */
    fun unpark(
        id: QuicStreamId,
        waiter: Waiter,
    ) {
        val entry = entries.getOrDefault(id.id, absent)
        entry.unpark(waiter)
        collect(entry)
    }

    /**
     * The peer told us to stop sending on [id] with [code]: our side is reset with the same code, as RFC 9000 §3.5
     * asks, so the peer's stream completes and its credit comes back.
     */
    fun stopped(
        id: QuicStreamId,
        code: QuicAppErrorCode,
    ) {
        val entry = entries.getOrDefault(id.id, absent)
        if (entry === absent || entry.sent !is Side.Open) return
        io.shutdown(id, ShutdownSide.Write, code.value)
        entry.sendEnded(Side.Stopped)
        collect(entry)
    }

    /** Stops reading [id] with [code] unless the peer's side ended already: the peer is told to stop sending. */
    fun stopReading(
        id: QuicStreamId,
        code: QuicAppErrorCode,
    ) {
        val entry = entries.getOrDefault(id.id, absent)
        if (entry === absent || entry.received !is Side.Open) return
        io.shutdown(id, ShutdownSide.Read, code.value)
        entry.receiveEnded(Side.Stopped)
        collect(entry)
    }

    /** How many streams hold an entry: the live ones. */
    fun liveStreams(): Int = entries.size

    /** The reader parked on [id], without making an entry for a stream that has none. */
    fun readerOf(id: QuicStreamId): Waiter = entries.getOrDefault(id.id, absent).reader

    /** The writer parked on [id], without making an entry for a stream that has none. */
    fun writerOf(id: QuicStreamId): Waiter = entries.getOrDefault(id.id, absent).writer

    /** [collect] for [id]'s entry, if the stream still has one. */
    fun collect(id: QuicStreamId) = collect(entries.getOrDefault(id.id, absent))

    /** Our side of [id] ended [how]: our FIN went, we reset it, or the peer stopped it. */
    fun sendEnded(
        id: QuicStreamId,
        how: Side.Ended,
    ) {
        val entry = entries.getOrDefault(id.id, absent)
        if (entry === absent) return
        entry.sendEnded(how)
        collect(entry)
    }

    /** The peer's side of [id] ended for us [how]: its FIN was read, it reset its side, or we stopped it. */
    fun receiveEnded(
        id: QuicStreamId,
        how: Side.Ended,
    ) {
        val entry = entries.getOrDefault(id.id, absent)
        if (entry === absent) return
        entry.receiveEnded(how)
        collect(entry)
    }

    /**
     * Lets [entry] go once its stream is spent: both directions ended, however each did (a direction this side has no
     * part in counts as ended), and nothing waits on it. The table then holds only live streams, so it does not grow with every stream ever opened.
     */
    fun collect(entry: StreamEntry) {
        if (entry === absent || entry.reader !== Nobody || entry.writer !== Nobody) return
        if (entry.received is Side.Open) return
        if (entry.sent is Side.Open && sends(entry.id)) return
        entries.remove(entry.id.id)
        spare.add(entry)
    }

    /** Whether this side sends on [id]: every bidirectional stream, and the unidirectional ones it opened. */
    private fun sends(id: QuicStreamId): Boolean = id.id and UNI_BIT == 0L || !initiatedByPeer(id)

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

    /** Whether an operation is parked until the peer answers: an open at its stream limit, a read, or a write. */
    fun waitsOnPeer(): Boolean {
        if (opens.isNotEmpty() || writeWaiters.isNotEmpty()) return true
        var parked = false
        entries.forEachValue { parked = parked || it.reader !== Nobody || it.writer !== Nobody }
        return parked
    }

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
        const val UNI_BIT = 2L
    }
}
