@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How the peer's side of a held stream stood at the end: still open, finished by its FIN, or reset. */
internal enum class HeldEnd { Open, Finished, Reset }

/** What a read after the end takes for its stream. */
internal sealed interface HeldTake {
    /** Received bytes no read had returned; the caller's from here on. */
    class Bytes(
        val buffer: PlatformBuffer,
    ) : HeldTake

    /** The peer's FIN, or a stream that was already spent at the end. */
    data object Finished : HeldTake

    data object Reset : HeldTake

    /** Nothing more: the stream was open at the end and its peer had not finished it, so the read meets the close. */
    data object Closed : HeldTake
}

/** Whether a stream still owes its reader something after the end. */
internal enum class Owed { Yes, No }

/**
 * What one stream had received and no read had returned when its connection ended, read out of quiche at the end so the
 * reads after it get it (RFC 9000 §10.2 ends the connection, not what it delivered). Each chunk and the terminal go to
 * one read: [next] moves past them by compare-and-set.
 */
@Published
internal class HeldStream(
    private val chunks: List<PlatformBuffer>,
    private val end: HeldEnd,
) {
    /** The next chunk's index; [chunks].size once only the terminal is left, one past it once a read took that. */
    private val next = AtomicInt(0)

    fun take(): HeldTake {
        while (true) {
            val at = next.load()
            if (at < chunks.size) {
                if (next.compareAndSet(at, at + 1)) return HeldTake.Bytes(chunks[at])
                continue
            }
            if (at == chunks.size) next.compareAndSet(at, at + 1)
            return when (end) {
                HeldEnd.Open -> HeldTake.Closed
                HeldEnd.Finished -> HeldTake.Finished
                HeldEnd.Reset -> HeldTake.Reset
            }
        }
    }

    /** Whether a read is still owed bytes, a FIN or a reset; an open stream with no bytes left owes only the close. */
    fun owed(): Owed {
        val at = next.load()
        return when {
            at < chunks.size -> Owed.Yes
            at > chunks.size || end == HeldEnd.Open -> Owed.No
            else -> Owed.Yes
        }
    }

    /** The caller let the stream go: the chunks no read took are freed. */
    fun free() {
        val from = next.exchange(chunks.size + 1)
        for (index in from until chunks.size) chunks[index].freeIfNeeded()
    }
}

/**
 * What a connection held for its readers when it ended, by stream, and the streams that were still [live] then: a read
 * of a stream nothing was held for reads its end when the stream was already spent, as before the end, and meets the
 * close when it was live. Built once inside at the end, immutable after; any thread reads it.
 */
internal class HeldReads(
    private val held: Map<Long, HeldStream>,
    private val live: Set<Long>,
) {
    /** The streams that still owe their reader something. */
    fun owing(): Set<QuicStreamId> = held.filterValues { it.owed() == Owed.Yes }.keys.mapTo(HashSet()) { QuicStreamId(it) }

    fun take(stream: QuicStreamId): HeldTake {
        val one = held.getOrElse(stream.id) { return if (stream.id in live) HeldTake.Closed else HeldTake.Finished }
        return one.take()
    }

    fun owed(stream: QuicStreamId): Owed = held.getOrElse(stream.id) { return Owed.No }.owed()

    fun free(stream: QuicStreamId) = held.getOrElse(stream.id) { return }.free()

    /** The connection was released: what no read took is freed; a read after this meets the close. */
    fun freeAll() = held.values.forEach { it.free() }
}
