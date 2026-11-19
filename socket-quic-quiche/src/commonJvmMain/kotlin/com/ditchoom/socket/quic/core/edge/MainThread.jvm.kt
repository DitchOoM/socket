package com.ditchoom.socket.quic.core.edge

/** [thread], compared by identity: no lock and no allocation on the call path. */
internal actual class MainThread(
    private val thread: Thread,
) {
    actual fun isCurrent(): Boolean = Thread.currentThread() === thread
}
