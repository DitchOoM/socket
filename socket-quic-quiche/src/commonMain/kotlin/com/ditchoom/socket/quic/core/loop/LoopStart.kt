package com.ditchoom.socket.quic.core.loop

/** Whether a loop's thread started. */
internal sealed interface LoopStart {
    data object Started : LoopStart

    /** The platform refused the thread; [cause] is what it threw, and the loop never ran. */
    class Refused(
        val cause: Throwable,
    ) : LoopStart
}

/** Starts the thread of a fully built loop: [startLoop] on the platform. */
internal typealias LoopStarter = (loop: Loop, name: String) -> LoopStart

/** Runs a fully built [loop] on a dedicated platform thread of its own, named [name], until the loop stops. */
internal expect fun startLoop(
    loop: Loop,
    name: String,
): LoopStart
