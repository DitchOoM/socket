package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.udp.Thrown

/** Whether a loop's thread started. */
internal sealed interface LoopStart {
    data object Started : LoopStart

    /** The platform refused the thread, with what it threw: the loop never ran. */
    class Refused(
        val thrown: Thrown,
    ) : LoopStart
}

/** Runs a fully built [loop] on a dedicated platform thread of its own, named [name], until the loop stops. */
internal expect fun startLoop(
    loop: Loop,
    name: String,
): LoopStart
