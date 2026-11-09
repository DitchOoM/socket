package com.ditchoom.socket.mainthread

import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.socket.connect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plain TCP, every public step on the main-like thread: the server's allocate, bind and accept; the
 * client's connect (to `localhost`, so it resolves a name), write, read and close.
 */
abstract class TcpMainThreadSafetyScenarios(
    private val client: JvmClientImplementation,
) {
    @Test
    fun serverBindAcceptAndClientRoundTrip() =
        client.selected {
            runOnMainLike {
                val server = ServerSocket.allocate()
                val accepted = server.bind(host = "localhost")
                assertOnMainLike("ServerSocket.bind")
                val echo =
                    launch {
                        val peer = accepted.first()
                        assertOnMainLike("accept")
                        val request = peer.readString()
                        peer.writeString("echo:$request")
                        peer.close()
                    }
                ClientSocket.connect(server.port(), "localhost") { socket ->
                    assertOnMainLike("ClientSocket.connect")
                    socket.writeString("hello")
                    assertEquals("echo:hello", socket.readString())
                    assertOnMainLike("read")
                }
                assertOnMainLike("close")
                echo.join()
                server.close()
                assertOnMainLike("ServerSocket.close")
            }
        }
}

class TcpAsyncChannelMainThreadSafetyTest : TcpMainThreadSafetyScenarios(JvmClientImplementation.AsyncChannel)

class TcpNioSelectorMainThreadSafetyTest : TcpMainThreadSafetyScenarios(JvmClientImplementation.NioSelector)

class TcpNioBlockingMainThreadSafetyTest : TcpMainThreadSafetyScenarios(JvmClientImplementation.NioBlocking)
