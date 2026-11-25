package com.ditchoom.socket.quic.core.observe

/** What a connection's timer did, counted by whoever is inside the connection; a test reads it between operations. */
@Confined
internal class TimerCounts {
    /** Turns that asked the timer to re-arm. */
    var rearms = 0L
        private set

    /** Re-arms that moved the loop's deadline: each is one arming of the connection's deadline on its loop. */
    var moves = 0L
        private set

    fun rearmed() {
        rearms++
    }

    fun moved() {
        moves++
    }
}
