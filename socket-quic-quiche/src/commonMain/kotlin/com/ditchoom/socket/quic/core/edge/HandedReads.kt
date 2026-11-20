package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.LongQueue
import com.ditchoom.socket.quic.core.stream.InHand
import com.ditchoom.socket.quic.core.stream.Waiter

/**
 * Reads that handed their caller bytes, oldest first, with their streams: until a caller takes its bytes, an end counts
 * its stream as unread. The front ones already taken are let go as more come, so it holds about the reads in flight.
 * Inside the connection only.
 */
@Confined
internal class HandedReads {
    private val reads = ArrayDeque<Waiter>()
    private val streams = LongQueue()

    fun add(
        stream: QuicStreamId,
        read: Waiter,
    ) {
        while (reads.isNotEmpty() && reads.first().inHand() == InHand.No) {
            reads.removeFirst()
            streams.takeOldest()
        }
        reads.addLast(read)
        streams.addLast(stream.id)
    }

    /** At the end: the reads whose caller has not taken its bytes yet. Empties this. */
    fun atEnd(): InHandAtEnd {
        val still = InHandAtEnd()
        while (reads.isNotEmpty()) {
            val read = reads.removeFirst()
            val stream = QuicStreamId(streams.takeOldest())
            if (read.inHand() == InHand.Yes) still.add(stream, read)
        }
        return still
    }
}

/** The reads that were handed bytes their caller had not taken when the connection ended. Any thread, once built. */
internal class InHandAtEnd {
    private val reads = ArrayList<Waiter>()
    private val streams = ArrayList<QuicStreamId>()

    fun add(
        stream: QuicStreamId,
        read: Waiter,
    ) {
        streams.add(stream)
        reads.add(read)
    }

    fun streams(): Set<QuicStreamId> = streams.toSet()

    /** Hands [each] the stream of every read whose caller took its bytes since the end. */
    fun forEachTaken(each: (QuicStreamId) -> Unit) {
        for (index in reads.indices) if (reads[index].inHand() == InHand.No) each(streams[index])
    }
}
