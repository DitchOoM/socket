package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.LongSet

/** Whether a stream quiche reports is one the peer opened that this side sees for the first time. */
internal enum class Sight { First, Known }

/** The streams of each kind the peer opened that this side was told of. Inside the connection only. */
@Confined
internal class PeerOpened(
    private val role: QuicRole,
) {
    private val bidirectional = PeerStreamIds(StreamKind.Bidirectional.firstPeer(role))
    private val unidirectional = PeerStreamIds(StreamKind.Unidirectional.firstPeer(role))

    /** Whether [id] is a stream the peer opened, seen now for the first time; from here it counts as seen. */
    fun firstSight(id: QuicStreamId): Sight {
        val shape = shapeOf(id, role)
        val ids =
            when (shape.kind()) {
                StreamKind.Bidirectional -> bidirectional
                StreamKind.Unidirectional -> unidirectional
            }
        return when (shape.opener()) {
            Opener.Local -> Sight.Known
            Opener.Peer -> if (ids.seen(id.id)) Sight.Known else Sight.First.also { ids.see(id.id) }
        }
    }
}

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
