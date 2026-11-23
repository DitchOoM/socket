package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.stream.ReadChunk

/**
 * The buffer a read holds until it hands it to its caller, already made into the answer the bytes will be:
 * [ReadAnswer.Data]; [None] once handed over or freed.
 */
internal sealed interface Spare {
    data object None : Spare
}

/**
 * What a stream's reads keep between them: how many bytes the next asks for, its reader's (a stream has one reader at
 * a time), and the peer's reset they met, which any thread reads.
 */
@CallerSide
internal class StreamReads {
    var chunk: ReadChunk = ReadChunk.Initial
        private set

    val reset = PeerResetSeen()

    /** A read that [asked] for its chunk got [got] bytes: the next read's size follows [ReadChunk.after]. */
    fun read(
        asked: ReadChunk,
        got: Int,
    ) {
        chunk = asked.after(asked.bytes, got)
    }
}
