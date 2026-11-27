package com.ditchoom.socket.quic

import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** JS has no QUIC: every entry point is refused with [QuicRefusal.NoQuicOnThisPlatform], typed, before anything opens. */
class JsQuicRefusalTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"))
    private val tls = QuicTlsConfig(certChainPath = "unused", privKeyPath = "unused")
    private val local = QuicPeer.Candidates(listOf(QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), 4433)), "localhost")

    @Test
    fun aConnectIsRefusedAsAValue() =
        runTest {
            val refused = assertIs<QuicConnect.Refused>(connectQuic(local, options))
            assertEquals<QuicRefusal>(QuicRefusal.NoQuicOnThisPlatform, refused.refusal)
        }

    @Test
    fun aServeIsRefusedAsAValue() =
        runTest {
            val refused = assertIs<QuicServe.Refused>(serveQuic(tls, options))
            assertEquals<QuicRefusal>(QuicRefusal.NoQuicOnThisPlatform, refused.refusal)
        }

    @Test
    fun theLegacyEntryPointsRaiseTheRefusal() =
        runTest {
            val connect = assertFailsWith<QuicRefusedException> { withQuicConnection(local, options) { } }
            assertEquals<QuicRefusal>(QuicRefusal.NoQuicOnThisPlatform, connect.refusal)
            val serve = assertFailsWith<QuicRefusedException> { withQuicServer(tlsConfig = tls, quicOptions = options) { } }
            assertEquals<QuicRefusal>(QuicRefusal.NoQuicOnThisPlatform, serve.refusal)
        }
}
