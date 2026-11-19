package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.MoveTo
import com.ditchoom.socket.quic.core.conn.MovedWork
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.conn.NetworkWork
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.Moved
import com.ditchoom.socket.quic.core.udp.SocketFault

/** Whether an endpoint's socket is still being moved: its new socket is not ready yet. */
internal sealed interface Relocating {
    data object No : Relocating

    /** The new socket is not ready; its network is assumed to have a round trip of [prior]. */
    class Yes(
        val prior: RttPrior,
    ) : Relocating
}

/**
 * What a socket's answer to a move does to this route's connection: it is told where its socket is now, or it ends
 * (as its socket could not be made, or as its address went away). Answers whether the move is still under way.
 */
internal fun Route.Fixed.answered(
    moved: Moved,
    prior: RttPrior,
): Relocating =
    when (moved) {
        is Moved.To -> {
            conn.submitFromLoop(MovedWork(MoveTo(moved.local, prior)))
            Relocating.No
        }
        is Moved.Refused -> {
            conn.endWithLoop(ConnEnd.SocketFailed(SocketFault.MoveRefused(moved.thrown)))
            Relocating.No
        }
        is Moved.Unsupported -> {
            conn.submitFromLoop(NetworkWork(NetworkChange.Moved))
            Relocating.No
        }
        is Moved.Arriving -> Relocating.Yes(prior)
    }
