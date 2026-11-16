package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Split out of SocketBuffersTests: needs the core's echo server and client, re-admitted with step 4. */
class SocketBuffersEchoTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

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
