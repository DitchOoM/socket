package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheStreamIter
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.FixedPool
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
 *
 * quiche lets a stream go only once the application touches it after both directions are over: a read that meets
 * the peer's FIN or reset, or a write or capacity query on a stream the peer stopped. Until then the peer's stream
 * credit stays spent, so every stream quiche reports readable or writable is touched here, whether or not a caller
 * waits on it; what arrives on a stream nobody will read is read into a loop's [DrainSlot] and dropped.
 */
@Confined
internal class StreamTable(
    private val q: QuicheConnRef,
    val io: StreamIo,
    private val role: QuicRole,
    drains: FixedPool<DrainSlot>,
) {
    private val drain = StreamDrain(io, drains)

    private val entries = LongObjectMap<StreamEntry>()

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
    private val peerBidi = PeerStreamIds(if (role == QuicRole.Client) 1L else 0L)
    private val peerUni = PeerStreamIds(if (role == QuicRole.Client) 3L else 2L)
    private var nextBidi: Long = if (role == QuicRole.Client) 0L else 1L
    private var opened: Long = 0L

    var state: Streams = Streams.Open
        private set

    /** Monotonic: received data handed to a parked reader, and streams the peer opened, counted as they are found. */
    var handedUp = 0L
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
     * The peer told us to stop sending on [id]: our side ended. quiche answered the STOP_SENDING with the RESET_STREAM
     * RFC 9000 §3.5 asks for when it read it, so nothing is sent here.
     */
    fun stopped(id: QuicStreamId) = sendEnded(id, Side.Stopped)

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
        if (entry.received !is Side.Ended) return
        if (entry.sent !is Side.Ended && sends(entry.id)) return
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

    /** Whether a write or an open is parked until the peer gives room. */
    fun backlog(): Backlog = if (opens.isNotEmpty() || writeWaiters.isNotEmpty()) Backlog.Waiting else Backlog.None

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
        if (io.send(id, 0L, 0, WriteEnd.More).code() == QUICHE_ERR_STREAM_LIMIT) return Opened.AtLimit
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
        wakeWriters()
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
        entries.forEachValue { it.endWaiters(end) }
        while (opens.isNotEmpty()) opens.removeFirst().connectionEnded(end)
    }

    private fun readable(id: QuicStreamId): Boolean {
        val entry = entries.getOrDefault(id.id, absent)
        when {
            entry !== absent -> arrived(entry)
            initiatedByPeer(id) && !peerIds(id).seen(id.id) -> {
                peerIds(id).see(id.id)
                entry(id)
                incoming.addLast(id.id)
                handedUp++
            }
            // A spent stream quiche still holds: what arrived after its entry went (the peer's reset after we stopped
            // reading it) is read, so quiche lets it go.
            else -> drain.drop(id)
        }
        return true
    }

    /** Something arrived on [entry]'s stream: its reader takes it, or, when nobody will read it, it is dropped. */
    private fun arrived(entry: StreamEntry) {
        if (entry.reader !== Nobody) handedUp++
        if (entry.reader !== Nobody || entry.received is Side.Open) return entry.wakeReader(this)
        when (val side = drain.drop(entry.id)) {
            is Side.Ended -> entry.receiveEnded(side)
            is Side.Open, is Side.Abandoned -> Unit
        }
        collect(entry)
    }

    private fun peerIds(id: QuicStreamId): PeerStreamIds = if (id.id and UNI_BIT == 0L) peerBidi else peerUni

    /**
     * The caller closed [id] without reading it to the end: what arrived is read and dropped now, and what arrives
     * later as it comes, until the peer's FIN or reset; the peer is not told to stop.
     */
    fun abandon(id: QuicStreamId) {
        val entry = entries.getOrDefault(id.id, absent)
        if (entry === absent) return
        entry.abandonReceive()
        arrived(entry)
    }

    /**
     * Hands out every stream quiche marked writable: a parked writer retries; a stream nobody writes is asked its
     * capacity, which is how a stream the peer stopped ends our side and, once over, leaves quiche.
     */
    private fun wakeWriters() {
        while (true) {
            val next = q.api.connStreamWritableNext(q.handle)
            if (next < 0L) return
            writable(QuicStreamId(next))
        }
    }

    private fun writable(id: QuicStreamId) {
        if (writeWaiters.remove(id.id)) return entry(id).wakeWriter(this)
        when (io.capacity(id)) {
            QUICHE_ERR_STREAM_STOPPED.toLong() -> stopped(id)
            else -> Unit
        }
    }

    private fun initiatedByPeer(id: QuicStreamId): Boolean = (id.id and 1L == 1L) == (role == QuicRole.Client)

    private companion object {
        const val STREAM_ID_STEP = 4L
        const val UNI_BIT = 2L
    }
}
