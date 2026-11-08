package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A server that fails on a peer that is not the test's own client says so. This is the shape of the
 * `nullBytesInData` failure: another socket connects to the server's port and closes at once, the
 * server accepts it first and reads end-of-stream within milliseconds.
 */
class AcceptLogTests {
    @Test
    fun aServerThatFailsOnAStrangerSaysItWasNotThisTestsClient() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val elsewhere = ServerSocket.allocate()
            elsewhere.bind()
            val accepts = AcceptLog()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val served = scope.async { serverFlow.serveEach(accepts) { it.readBuffer(5.seconds) } }

                val stranger = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                stranger.open(server.port(), hostname = "127.0.0.1")
                stranger.close()

                // The test's own client is connected elsewhere, so the stranger is the only peer this
                // server can accept: which one it sees first is then not a race.
                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                client.open(elsewhere.port(), hostname = "127.0.0.1")
                accepts.client(client)

                val failure = assertIs<AssertionError>(runCatching { served.await() }.exceptionOrNull())
                val message = failure.message.orEmpty()
                assertContains(message, "#1 is NOT this test's client", message = message)
                assertContains(
                    message,
                    "this test's client connected from local port ${client.localPort()} to ${elsewhere.port()}",
                    message = message,
                )
                assertContains(message, "server handler for #1 threw", message = message)
                client.close()
            } finally {
                scope.cancel()
                server.close()
                elsewhere.close()
            }
        }
}
