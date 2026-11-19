package com.ditchoom.socket.quic.core.stream

import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.STREAMS_FULL
import com.ditchoom.socket.quic.STREAMS_IDS
import com.ditchoom.socket.quic.STREAMS_ID_BYTES
import com.ditchoom.socket.quic.STREAMS_LAYOUT
import com.ditchoom.socket.quic.STREAMS_READABLE
import com.ditchoom.socket.quic.STREAMS_SPILLED
import com.ditchoom.socket.quic.STREAMS_WRITABLE
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined

/** Whether [StreamsBlock] holds quiche's readable streams, or more were readable than fit and none are written. */
internal enum class ReadableList { Listed, Spilled }

/** Whether quiche's writable streams were all taken, or the block filled first and quiche may hold more. */
internal enum class WritableTail { Taken, MaybeMore }

/**
 * A connection's streams as quiche reports them on one turn, read in one native call ([QuicheApi.connStreams]) into the
 * [bytes] at [address] the connection owns; every read after [take] is of that memory, in native byte order as C wrote
 * it. A turn with more readable streams than fit walks quiche's iterator, and one whose writable streams filled the
 * block takes the rest one call each.
 */
@Confined
internal class StreamsBlock(
    internal val q: QuicheConnRef,
    internal val address: Long,
    bytes: Int,
) {
    private val capacity = (bytes - STREAMS_IDS) / STREAMS_ID_BYTES

    /** Hands [readable] every stream quiche reports readable, then [writable] every one it marked writable, taking the marks. */
    inline fun each(
        readable: (QuicStreamId) -> Unit,
        writable: (QuicStreamId) -> Unit,
    ) {
        take()
        when (readableList()) {
            ReadableList.Listed -> for (i in 0 until readable()) readable(id(i))
            ReadableList.Spilled -> {
                val iter = q.api.connReadable(q.handle)
                if (!iter.isExhausted) {
                    try {
                        while (
                            q.api.streamIterNextId(iter).fold(end = { false }, stream = {
                                readable(it)
                                true
                            })
                        ) {
                            Unit
                        }
                    } finally {
                        q.api.streamIterFree(iter)
                    }
                }
            }
        }
        val first = readable()
        for (i in first until first + writable()) writable(id(i))
        when (writableTail()) {
            WritableTail.Taken -> Unit
            WritableTail.MaybeMore ->
                while (true) {
                    val next = q.api.connStreamWritableNext(q.handle)
                    if (next < 0L) break
                    writable(QuicStreamId(next))
                }
        }
    }

    /** Reads quiche's readable streams and takes its writable ones. */
    fun take() {
        q.api.connStreams(q.handle, address, capacity)
    }

    fun readableList(): ReadableList = if (intAt(STREAMS_READABLE) == STREAMS_SPILLED) ReadableList.Spilled else ReadableList.Listed

    /** The readable ids listed: none when [readableList] is [ReadableList.Spilled]. */
    fun readable(): Int = maxOf(intAt(STREAMS_READABLE), 0)

    fun writable(): Int = intAt(STREAMS_WRITABLE)

    fun writableTail(): WritableTail = if (intAt(STREAMS_FULL) == 0) WritableTail.Taken else WritableTail.MaybeMore

    /** The [index]th id: the readable ones first, then the writable ones. */
    fun id(index: Int): QuicStreamId = QuicStreamId(UnsafeMemory.getLong(address + STREAMS_IDS + index * STREAMS_ID_BYTES))

    private fun intAt(offset: Int): Int = UnsafeMemory.getInt(address + offset)

    companion object {
        /** The bytes a block of [ids] ids takes. */
        fun bytesFor(ids: Int): Int = STREAMS_IDS + ids * STREAMS_ID_BYTES

        /** The ids a connection's block holds. */
        const val IDS = 64
    }
}

/** The loaded backend lays [QuicheApi.connStreams]' block out differently from this code: a stale or mismatched native library. */
internal class StreamsLayoutMismatch(
    val expected: Int,
    val reported: Int,
)

/** Whether the loaded backend writes the block this code reads. */
internal sealed interface StreamsLayoutCheck {
    data object Same : StreamsLayoutCheck

    class Differs(
        val mismatch: StreamsLayoutMismatch,
    ) : StreamsLayoutCheck
}

internal fun streamsLayoutOf(api: QuicheApi): StreamsLayoutCheck =
    when (val reported = api.connStreamsLayout()) {
        STREAMS_LAYOUT -> StreamsLayoutCheck.Same
        else -> StreamsLayoutCheck.Differs(StreamsLayoutMismatch(STREAMS_LAYOUT, reported))
    }
