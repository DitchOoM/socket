package com.ditchoom.socket.quic

internal actual fun reportOnThisThread(failure: Throwable) {
    try {
        failure.printStackTrace()
    } catch (_: Throwable) {
        // Nothing left to tell: the timer thread must survive a failure to report.
    }
}
