package com.ditchoom.socket.quic.core.sched

/**
 * A platform thread of a [HandlerPool]: started once, it runs its body to the end and is never reused. Only the thread
 * itself parks; any thread may wake it, and a wake that comes before the park is not lost.
 */
internal expect class PoolThread {
    /** Any thread: the thread returns from its current or next [park]. */
    fun unpark()

    /** This thread only: waits until [unpark] is called, or returns at once if it was called since the last park. */
    fun park()
}

/** Starts a daemon platform thread named [name] that runs [body] with its own handle, then ends. */
internal expect fun startPoolThread(
    name: String,
    body: (PoolThread) -> Unit,
)
