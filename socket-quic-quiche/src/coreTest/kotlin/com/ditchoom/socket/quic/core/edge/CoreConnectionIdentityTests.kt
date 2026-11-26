package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicPeer
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.serveQuicOnCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Two connections alive at once can be told apart: a connection's [com.ditchoom.socket.quic.QuicConnectionIdentity] is not
 * blank, does not change over the connection's life, and differs between concurrent connections. A consumer whose clients
 * all re-authenticated in lockstep could not say which log line belonged to which client without it. A real connection
 * answers a known wire id, and every backend of the platform must bind it for that to hold.
 */
class CoreConnectionIdentityTests {
    @Test
    fun concurrentConnectionsHaveDistinctStableSessionIds() =
        coreTest("CoreConnectionIdentityTests.concurrentConnectionsHaveDistinctStableSessionIds") {
            val served =
                serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), options) { listener ->
                    val first = connect(listener.port)
                    val named = first.identity.session
                    val second = connect(listener.port)

                    assertTrue(named.hex.isNotBlank(), "a connection that cannot name itself leaves a log unanswerable")
                    assertEquals(named, first.identity.session, "the session id is stable for the connection's whole life")
                    val wire = assertIs<QuicWireConnectionId.Known>(first.identity.wire, "a real connection reports the wire id")
                    assertTrue(wire.hex.isNotBlank(), "Known means a backend read a connection id")
                    assertNotEquals(
                        named,
                        second.identity.session,
                        "two concurrent connections report one session id, so a log cannot tell them apart",
                    )
                    first.close(QuicCloseChoice.Immediate())
                    second.close(QuicCloseChoice.Immediate())
                }
            assertIs<QuicServe.Serving<Unit>>(served)
            Unit
        }

    private suspend fun connect(port: Int): QuicConnection {
        val peer = QuicPeer.Candidates(listOf(QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), port)), "localhost")
        return assertIs<QuicConnect.Connected<QuicConnection>>(connectQuicOnCore(peer, options, TransportConfig(), WAIT)).value
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val WAIT = 10.seconds
        val options =
            com.ditchoom.socket.quic
                .QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
    }
}
