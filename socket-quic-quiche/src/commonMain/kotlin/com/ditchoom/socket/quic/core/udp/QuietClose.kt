package com.ditchoom.socket.quic.core.udp

/**
 * Runs [close], a close of a socket that is failing or given up: what it throws is dropped, as that socket's move or
 * connection already ends with the failure that came first, and no other socket on the loop is touched by it.
 */
internal inline fun closedQuietly(close: () -> Unit) {
    try {
        close()
    } catch (_: Exception) {
        // The socket is let go either way; its io answers for its own close.
    }
}
