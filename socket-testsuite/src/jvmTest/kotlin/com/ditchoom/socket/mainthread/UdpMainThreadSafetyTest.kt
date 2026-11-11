@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.mainthread

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.udp.UdpSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * UDP, every public step on the main-like thread: an addressed bind, a connected channel to
 * `localhost` (so it resolves a name), a send each way, both receives, both closes.
 */
class UdpMainThreadSafetyTest {
    @Test
    fun bindConnectSendReceiveClose() =
        runOnMainLike {
            val server = UdpSocket.bind(localHost = "127.0.0.1")
            assertOnMainLike("UdpSocket.bind")
            val client = UdpSocket.connect("localhost", server.localAddress.port)
            assertOnMainLike("UdpSocket.connect")
            try {
                client.send(text("ping"))
                val request = assertIs<DatagramReadResult.Received>(server.receive())
                assertOnMainLike("receive")
                assertEquals("ping", request.datagram.payload.utf8())
                server.send(text("pong"), to = request.datagram.peer)
                request.datagram.payload.freeIfNeeded()
                val reply = assertIs<DatagramReadResult.Received>(client.receive())
                assertEquals("pong", reply.datagram.payload.utf8())
                reply.datagram.payload.freeIfNeeded()
            } finally {
                client.close()
                server.close()
            }
            assertOnMainLike("close")
        }

    @Test
    fun resolveAName() =
        runOnMainLike {
            UdpSocket.resolve("localhost", 9)
            assertOnMainLike("UdpSocket.resolve")
        }

    private fun text(s: String): PlatformBuffer =
        BufferFactory.deterministic().allocate(s.length).apply {
            writeString(s, Charset.UTF8)
            resetForRead()
        }

    private fun PlatformBuffer.utf8(): String = readString(remaining(), Charset.UTF8)
}
