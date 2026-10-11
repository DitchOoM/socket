package com.ditchoom.socket

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio.NioServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [NioServerSocket], the server `ServerSocket.allocate` returns where NIO2 is missing (Android API 23-25).
 * A JVM always has NIO2, so nothing else here exercises it; the API 24 emulator lane runs it for real.
 */
class NioServerSocketTests {
    @Test
    fun aClientRoundTripsThroughTheNioServer() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val server = NioServerSocket()
                val accepted = server.bind()
                assertTrue(server.isListening(), "bound")
                val port = server.port()
                assertTrue(port > 0, "an ephemeral port, was $port")
                val echo =
                    launch {
                        val client = accepted.first()
                        try {
                            client.writeString(client.readString())
                        } finally {
                            client.close()
                        }
                    }
                val client = NioClientSocket()
                try {
                    client.open(port, "localhost")
                    client.writeString("hello")
                    assertEquals("hello", client.readString())
                } finally {
                    client.close()
                }
                echo.join()
                server.close()
                assertFalse(server.isListening(), "closed")
            }
        }

    @Test
    fun closingTheServerEndsTheAcceptFlow() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val server = NioServerSocket()
                val accepted = server.bind()
                var completed = false
                val collector =
                    launch {
                        accepted.collect { it.close() }
                        completed = true
                    }
                server.close()
                collector.join()
                assertTrue(completed, "a close() ends the accept flow normally rather than throwing")
            }
        }
}
