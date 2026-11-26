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
): LoopStart {
    startLoopThread(loop, name)
    return LoopStart.Started
}
