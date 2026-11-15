package com.ditchoom.socket.quic.core.loop

/** A [Loop] and the platform thread that runs it. */
internal class RunningLoop(
    val loop: Loop,
    val thread: Thread,
)

/** Starts a dedicated platform thread for a fully built [loop]. Never a virtual thread: the loop blocks in its selector. */
internal fun startLoop(
    loop: Loop,
    name: String,
): RunningLoop {
    val thread = Thread(loop::run, name)
    thread.isDaemon = true
    thread.start()
    return RunningLoop(loop, thread)
}

/** Starts a dedicated platform thread for a fully built [loop]. Never a virtual thread: the loop blocks in its selector. */
internal fun startClientLoop(loop: ClientLoop): Thread {
    val thread = Thread(loop::run, loop.name)
    thread.isDaemon = true
    thread.start()
    return thread
}
