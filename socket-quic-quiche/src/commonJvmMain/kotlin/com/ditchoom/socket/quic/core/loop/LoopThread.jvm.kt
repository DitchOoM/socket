package com.ditchoom.socket.quic.core.loop

/** Starts a dedicated platform daemon thread for a fully built [loop]. Never a virtual thread: the loop blocks in its poller. */
internal fun startLoopThread(
    loop: Loop,
    name: String,
): Thread {
    val thread = Thread(loop::run, name)
    thread.isDaemon = true
    thread.start()
    return thread
}

internal actual fun startLoop(
    loop: Loop,
    name: String,
): LoopStart =
    try {
        startLoopThread(loop, name)
        LoopStart.Started
    } catch (refused: OutOfMemoryError) {
        // The system could not create the native thread: a refusal the caller is told of, not a process failure.
        LoopStart.Refused(refused)
    }
