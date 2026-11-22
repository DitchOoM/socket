package com.ditchoom.socket.quic

/** Hands [failure] to this thread's uncaught-exception handler; a handler that throws is ignored, it cannot be reported to. */
internal actual fun reportOnThisThread(failure: Throwable) {
    val thread = Thread.currentThread()
    try {
        // Nullable on Android (a thread that has ended has none); the default handler is the next resort.
        (thread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler())?.uncaughtException(thread, failure)
    } catch (_: Throwable) {
        // Nothing left to tell: the handler is the last resort, and the timer thread must survive it.
    }
}
