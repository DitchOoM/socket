package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where one server's accepted-connection handlers run, held from the server's construction until
 * [release]. Handlers are the application's code, so this is never a transport loop.
 *
 * On JVM/Android: threads of the server's own, started as handlers need them. A handler that blocks
 * keeps one of them and the next handler to become runnable gets another, up to the number of threads
 * `Dispatchers.IO` allows; past that, and after [release], a handler resumes on `Dispatchers.IO`.
 * On Kotlin/Native: [serverReceiveDispatcher], holding nothing.
 */
internal expect class ServerHandlerThreads(
    /** Names the threads, so a thread dump says which server a handler belongs to. */
    serverName: String,
) {
    val dispatcher: CoroutineDispatcher

    /** Ends the hold: idle threads exit, and a handler still running finishes on the thread it has. Idempotent. */
    fun release()
}
