package com.ditchoom.socket.quic.core.conn

/** Whether the thread that hands a connection work may run that work itself. */
internal sealed interface CallerPlace {
    /** Any thread but a main thread: enters a free connection, serves it and sends its output in place. */
    object MayEnter : CallerPlace

    /** A main or UI thread, or the loop itself: leaves the work for the loop, which does the system calls. */
    object HandToLoop : CallerPlace
}
