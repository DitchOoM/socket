package com.ditchoom.socket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [IoUringManager] releases the ring when its count of open sockets reaches zero, so the count must move
 * by exactly one per socket: up when its descriptor becomes the socket's, down when the socket gives it up.
 * Each test keeps a bound server open for its whole length, so a socket uncounted twice shows as a count
 * below the server's own instead of being hidden by the floor at zero.
 */
class IoUringSocketCountTests {
    /**
     * A client whose TCP connect succeeds and whose TLS handshake then fails closed its descriptor and
     * uncounted it, although it was only ever counted after the handshake.
     */
    @Test
    fun aClientWhoseTlsHandshakeFailsLeavesTheCountWhereItWas() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val accepted = server.bind()
            // The server hangs up on every connection, so a client's handshake reads end-of-stream.
            val acceptJob = launch(Dispatchers.Default) { accepted.collect { it.close() } }
            try {
                val withServer = IoUringManager.activeSockets
                val client = ClientSocket.allocate(TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds))
                val opened = runCatching { client.open(server.port(), "127.0.0.1") }
                assertTrue(opened.isFailure, "a TLS handshake with a server that hangs up must fail")
                client.close()
                assertSocketCountReturnsTo(withServer)
            } finally {
                acceptJob.cancel()
                server.close()
            }
        }

    /**
     * Closes racing on one socket — a read that saw the peer hang up, and the owner's close — close its
     * descriptor once and uncount it once. Two closers that both saw it open would close a descriptor
     * number the kernel may already have handed to another socket, and uncount a socket that is not theirs.
     */
    @Test
    fun concurrentClosesOfOneSocketUncountItOnce() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val server = ServerSocket.allocate()
            val acceptedSockets = Channel<ClientSocket>(Channel.UNLIMITED)
            val accepted = server.bind()
            val acceptJob = launch(Dispatchers.Default) { accepted.collect { acceptedSockets.send(it) } }
            try {
                val withServer = IoUringManager.activeSockets
                repeat(ROUNDS) { round ->
                    val client = ClientSocket.allocate()
                    client.open(server.port(), "127.0.0.1")
                    val serverSide = acceptedSockets.receive()
                    val gate = CompletableDeferred<Unit>()
                    val closers =
                        List(CLOSERS) { launch(Dispatchers.Default) { gate.await().also { client.close() } } } +
                            List(CLOSERS) { launch(Dispatchers.Default) { gate.await().also { serverSide.close() } } }
                    gate.complete(Unit)
                    closers.joinAll()
                    assertEquals(
                        withServer,
                        IoUringManager.activeSockets,
                        "round $round: $CLOSERS concurrent closes of a client and of its accepted socket must " +
                            "uncount each exactly once",
                    )
                }
            } finally {
                acceptJob.cancel()
                server.close()
            }
        }

    private companion object {
        const val ROUNDS = 100
        const val CLOSERS = 8
    }
}
