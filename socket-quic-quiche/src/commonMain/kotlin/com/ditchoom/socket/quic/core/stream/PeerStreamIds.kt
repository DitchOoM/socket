package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.LongSet

/**
 * The ids of one kind of stream the peer opened that this side was told of, so a stream quiche reports again after
 * its entry was let go is not taken for a new one. Ids arrive mostly in order: every id under [below] was seen, and
 * [ahead] holds the few seen past a gap. Inside the connection only.
 */
@Confined
internal class PeerStreamIds(
    first: Long,
) {
    private var below = first
    private val ahead = LongSet()

    fun seen(id: Long): Boolean = id < below || id in ahead

    fun see(id: Long) {
        if (id != below) return ahead.add(id)
        below += STEP
        while (ahead.remove(below)) below += STEP
    }

    private companion object {
        /** Ids of one kind of stream from one side are four apart. */
        const val STEP = 4L
    }
}
