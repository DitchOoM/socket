package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.InboxItem

/** Work for one connection, run inside it by whoever holds it. */
internal abstract class ConnWork : InboxItem<ConnWork>() {
    abstract fun run(inside: Inside)

    /** The connection ended with [end] before the work could run. Called once, instead of [run]. */
    abstract fun gone(end: ConnEnd)

    final override fun self(): ConnWork = this
}

/** Whether the thread that hands a connection work may run that work itself. */
internal enum class CallerPlace {
    /** Any thread but a main thread: enters a free connection, serves it and sends its output in place. */
    MayEnter,

    /** A main or UI thread, or the loop itself: leaves the work for the loop, which makes the system calls. */
    HandToLoop,
}
