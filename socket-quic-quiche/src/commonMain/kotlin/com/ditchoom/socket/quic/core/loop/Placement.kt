package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.core.udp.Thrown

/** What [LoopSet.place] answers. */
internal sealed interface Placement {
    class Placed(
        val running: RunningLoop,
    ) : Placement

    data object SetClosed : Placement

    /** The factory makes memory a JDK socket cannot read into. */
    data object NotJdkMemory : Placement

    /** The native stack could not open the loop's poller. */
    class PollerRefused(
        val refusal: SocketRefusal,
    ) : Placement

    /** The platform refused the loop's thread, with what it threw; the set holds no loop for it. */
    class ThreadRefused(
        val thrown: Thrown,
    ) : Placement
}
