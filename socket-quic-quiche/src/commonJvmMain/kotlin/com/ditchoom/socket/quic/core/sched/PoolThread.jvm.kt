package com.ditchoom.socket.quic.core.sched

import java.util.concurrent.locks.LockSupport

/** A platform daemon thread, parked with [LockSupport], whose permit keeps a wake that comes before the park. */
internal actual class PoolThread(
    private val thread: Thread,
) {
    actual fun unpark() = LockSupport.unpark(thread)

    actual fun park() = LockSupport.park(this)
}

/** Never a virtual thread: a pool thread runs application code that may block it. */
internal actual fun startPoolThread(
    name: String,
    body: (PoolThread) -> Unit,
) {
    val thread = Thread({ body(PoolThread(Thread.currentThread())) }, name)
    thread.isDaemon = true
    thread.start()
}
