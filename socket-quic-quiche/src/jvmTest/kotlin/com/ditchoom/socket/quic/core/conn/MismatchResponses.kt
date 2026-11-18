package com.ditchoom.socket.quic.core.conn

/** Runs [body] with a mismatch answered by sending at once, as a running library answers it, and restores the answer after. */
internal fun <T> MismatchResponse.Companion.sendingNowDuring(body: () -> T): T {
    val before = choose(MismatchResponse.SendNow)
    try {
        return body()
    } finally {
        choose(before)
    }
}
