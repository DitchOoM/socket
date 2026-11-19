package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.edge.connectCore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Split out of SocketBuffersTests: needs the core's echo server and client, re-admitted with step 4. */
class SocketBuffersEchoTests {
    @Test
    fun aServerAndAClientBothReportTheirGrantedBuffers() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port)
                try {
                    assertTrue(server.socket.buffers.receiveBytes > 0)
                    assertEquals(SocketBuffers.Default.receiveBytes, server.socket.buffers.requested.receiveBytes)
                    assertTrue(client.socket.buffers.receiveBytes > 0)
                    assertTrue(client.socket.buffers.sendBytes > 0)
                } finally {
                    client.connection.closeNow(QuicError.NoError)
                }
            }
        }
}
