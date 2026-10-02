package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class AcceptedSocketScopeTests {
    private fun tcpAvailable() = networkCapabilities().transports.contains(TransportKind.TCP)

    /**
     * A server cancelled while its handler waits on I/O still closes the accepted socket: the client sees its
     * stream end, where a socket left open would leave the client's read waiting out its deadline.
     */
    @Test
    fun aHandlerCancelledMidReadStillClosesItsAcceptedSocket() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val accepted = server.bind(host = "127.0.0.1")
            val handling = CompletableDeferred<Unit>()
            val serverJob =
                launch(Dispatchers.Default) {
                    accepted.serveEach { client ->
                        handling.complete(Unit)
                        client.readString()
                    }
                }
            val client = ClientSocket.connect(server.port(), hostname = "127.0.0.1")
            try {
                handling.await()
                serverJob.cancelAndJoin()
                assertFailsWith<SocketClosedException>("the server's close must reach the client as its stream's end") {
                    client.readBuffer(5.seconds)
                }
            } finally {
                client.close()
                server.close()
            }
        }
}
