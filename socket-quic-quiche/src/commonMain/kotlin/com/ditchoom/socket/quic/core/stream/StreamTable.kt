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
    early: EarlyCopy,
) {
    private val drain = StreamDrain(io, drains)

    /** What [unpark] finds for a stream that has no entry: nothing waits on it. */
    private val absent = StreamEntry(QuicStreamId(0L), StreamShape.LocalBidirectional, ParkedReads())

    private val entries = LongObjectMap(absent)

    /** Entries of spent streams, which [entry] gives the next streams: a lookup allocates nothing and boxes no id. */
    private val spare = ArrayList<StreamEntry>()

    private val writeWaiters = LongSet()
    private val incoming = LongQueue()
    private val peerOpened = PeerOpened(role)
    private var opened: Long = 0L

    /** The opens of each kind, and those waiting for the peer's credit. */
    val opens = OpenLanes(q, role)

    /** The streams whose reader is parked. */
    val reads = ParkedReads()

    var state: Streams = Streams.Open(StreamSend(q, io, early))
        private set

    /** Monotonic: received data handed to a parked reader, and streams the peer opened, counted as they are found. */
    var handedUp = 0L
        private set

    fun entry(id: QuicStreamId): StreamEntry {
        val held = entries.getOrDefault(id.id, absent)
        if (held !== absent) return held
        val shape = shapeOf(id, role)
        val made = if (spare.isEmpty()) StreamEntry(id, shape, reads) else spare.removeAt(spare.size - 1).reuse(id, shape)
        return made.also { entries[id.id] = it }
    }

    /** Whether [id] names a stream that is spent: one this side opened or was told of, whose entry was let go. */
    fun isSpent(id: QuicStreamId): Boolean {
        if (entries.containsKey(id.id)) return false
        val shape = shapeOf(id, role)
        return when (shape.opener()) {
            Opener.Peer -> true
            Opener.Local -> id.id < opens.lane(shape.kind()).next
        }
    }

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

    /** Stops reading [id] with [code]: what arrived is dropped, and the peer, unless its side ended, is told to stop. */
    fun stopReading(
        id: QuicStreamId,
        code: QuicAppErrorCode,
    ) {
        val entry = entries.getOrDefault(id.id, absent)
        if (entry === absent || entry.received !is Side.Open) return
        when (val side = drain.drop(id)) {
            is Side.Ended -> entry.receiveEnded(side)
            is Side.Open, is Side.Abandoned -> {
                io.shutdown(id, ShutdownSide.Read, code.value)
                entry.receiveEnded(Side.Stopped)
            }
        }
        collect(entry)
    }

    fun forEachEntry(each: (StreamEntry) -> Unit) = entries.forEachValue(each)

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
     * part in is [Side.NotPart] from the start), and nothing waits on it. The table then holds only live streams, so it
     * does not grow with every stream ever opened.
     */
    fun collect(entry: StreamEntry) {
        if (entry === absent || entry.reader !== Nobody || entry.writer !== Nobody) return
        if (entry.received !is Side.Ended || entry.sent !is Side.Ended) return
        entries.remove(entry.id.id)
        spare.add(entry)
    }

    /** Asks for [stream]'s writer to be retried once quiche says it can take bytes. */
    fun wantWritable(stream: QuicStreamId) {
        writeWaiters.add(stream.id)
    }

    /** The opens of both kinds that wait for the peer's credit. */
    fun opensWaiting(): Int = opens.waits()

    /** Whether a write or an open is parked until the peer gives room. */
    fun backlog(): Backlog = if (opensWaiting() > 0 || writeWaiters.isNotEmpty()) Backlog.Waiting else Backlog.None

    /** Whether an operation is parked until the peer answers: an open at its stream limit, a read, or a write. */
    fun waitsOnPeer(): Boolean {
        if (opensWaiting() > 0 || writeWaiters.isNotEmpty()) return true
        var parked = false
        entries.forEachValue { parked = parked || it.reader !== Nobody || it.writer !== Nobody }
        return parked
    }

    /**
     * Opens the next stream of [kind]: a zero-length send makes quiche create it (a read on a stream quiche has not
     * created answers INVALID_STREAM_STATE), which is also where the peer's limit of that kind shows. At the limit the
     * id is not spent.
     */
    fun tryOpen(
        kind: StreamKind,
        send: StreamSend,
    ): Opened {
        val lane = opens.lane(kind)
        if (send.send(QuicStreamId(lane.next), 0L, 0, WriteEnd.More).code() == QUICHE_ERR_STREAM_LIMIT) return Opened.AtLimit
        val id = lane.spend()
        entry(id)
        opened = id.id
        return Opened.Opened
    }

    /** The stream the last [tryOpen] that answered [Opened.Opened] created. */
    fun lastOpened(): QuicStreamId = QuicStreamId(opened)

    /** Retries what quiche's readable and writable streams, and the peer's stream credit of each kind, let go on. */
    fun wake() {
        val readable = q.api.connReadable(q.handle)
        if (!readable.isExhausted) wakeReaders(readable)
        wakeWriters()
        opens.wake(this)
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

    /** The close point of a close ending with [end]: no stream accepts data from here; a parked write or open is refused. */
    fun closePoint(end: ConnEnd) {
        if (state !is Streams.Open) return
        state = Streams.Closing(end)
        writeWaiters.clear()
        val writing = ArrayList<StreamEntry>()
        entries.forEachValue { if (it.writer !== Nobody) writing.add(it) }
        for (entry in writing) entry.wakeWriter(this)
        opens.refuseAll(this)
    }

    /** The connection ended with [end]: every waiting operation fails with it, and so does every later one. */
    fun endAll(end: ConnEnd) {
        if (state is Streams.Ended) return
        state = Streams.Ended(end)
        entries.forEachValue { it.endWaiters(end) }
        opens.endAll(end)
    }

    private fun readable(id: QuicStreamId): Boolean {
        val entry = entries.getOrDefault(id.id, absent)
        when {
            entry !== absent -> arrived(entry)
            peerOpened.firstSight(id) == Sight.First -> {
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
     * capacity, which is how a stream the peer stopped ends our side and, once over, leaves quiche. A stream still
     * replaying a rejected 0-RTT's bytes hands quiche those first, and its writer waits until they are in.
     */
    private fun wakeWriters() {
        while (true) {
            val next = q.api.connStreamWritableNext(q.handle)
            if (next < 0L) return
            writable(QuicStreamId(next))
        }
    }

    private fun writable(id: QuicStreamId) {
        when (val now = state) {
            is Streams.Open -> if (now.send.resume(id) == Replaying.Behind) return
            is Streams.Closing, is Streams.Ended -> Unit
        }
        if (writeWaiters.remove(id.id)) return entry(id).wakeWriter(this)
        if (io.capacity(id) == QUICHE_ERR_STREAM_STOPPED.toLong()) stopped(id)
    }
}
