package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.core.observe.LoopOnly

/** The tokens a wait found ready, in a preallocated array the loop reads by position. */
@LoopOnly
internal class ReadyTokens(
    capacity: Int,
) {
    private val tokens = IntArray(capacity)
    private var filled = 0

    val count: Int get() = filled

    fun tokenAt(i: Int): Int = tokens[i]

    fun clear() {
        filled = 0
    }

    /** A token beyond the capacity is dropped: its socket is still ready and shows up in the next wait. */
    fun add(token: Int) {
        if (filled < tokens.size) tokens[filled++] = token
    }
}
