package com.ditchoom.socket

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Runs [block] and closes this socket on every exit, a cancelled one included. A server handler that closes
 * its socket only after its last read or write never reaches the close when the test cancels the server while
 * the handler still waits on that I/O — an echo can reach the test's client before the server has seen its own
 * write complete — and the socket outlives the test.
 */
internal suspend inline fun <R> ClientSocket.closeAfter(block: () -> R): R =
    try {
        block()
    } finally {
        withContext(NonCancellable) { close() }
    }

/** Collects accepted sockets, running [handle] on each under [closeAfter]. */
internal suspend fun Flow<ClientSocket>.serveEach(handle: suspend (ClientSocket) -> Unit) =
    collect { client -> client.closeAfter { handle(client) } }
