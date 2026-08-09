package com.ditchoom.socket.quic

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [connectionsByAlpn] (routing semantics) and [QuicScope.negotiatedAlpn]
 * (the state-derived default on [QuicConnection]) over in-memory fakes. The end-to-end
 * proof — two protocols negotiated over real TLS on one UDP port — lives in the shared
 * `QuicServerTestSuite.alpnDemux_twoProtocolsShareOneListener` loopback test.
 */
class AlpnDemuxTests {
    /**
     * Fake [QuicServer]: `connections` drains a channel of pre-built scopes, mirroring the real
     * accept loop's one-handler-invocation-per-connection contract (handlers run concurrently,
     * `connections` returns when the accept channel closes).
     */
    private class FakeQuicServer(
        private val accepted: Channel<QuicScope>,
    ) : QuicServer {
        override val port: Int = 0

        override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
            coroutineScope {
                for (scope in accepted) {
                    launch { scope.handler() }
                }
            }

        override suspend fun close() {
            accepted.close()
        }
    }

    @Test
    fun negotiatedAlpn_derivesFromEstablishedState() =
        runQuicTest {
            val conn = MockQuicConnection(initialState = QuicConnectionState.Established("my-proto"))
            assertEquals("my-proto", conn.negotiatedAlpn)
        }

    @Test
    fun negotiatedAlpn_throwsWhenNotEstablished() =
        runQuicTest {
            val conn = MockQuicConnection(initialState = QuicConnectionState.Handshaking)
            assertFailsWith<IllegalStateException> { conn.negotiatedAlpn }
        }

    @Test
    fun connectionsByAlpn_routesEachConnectionToItsProtocolHandler() =
        runQuicTest {
            val accepted = Channel<QuicScope>(Channel.UNLIMITED)
            accepted.send(MockQuicConnection(initialState = QuicConnectionState.Established("proto-a")))
            accepted.send(MockQuicConnection(initialState = QuicConnectionState.Established("proto-b")))
            accepted.send(MockQuicConnection(initialState = QuicConnectionState.Established("proto-a")))
            accepted.close()

            val handled = mutableListOf<String>()
            FakeQuicServer(accepted).connectionsByAlpn(
                "proto-a" to { handled += "a:$negotiatedAlpn" },
                "proto-b" to { handled += "b:$negotiatedAlpn" },
            )

            assertEquals(listOf("a:proto-a", "a:proto-a", "b:proto-b"), handled.sorted())
        }

    @Test
    fun connectionsByAlpn_unmatchedProtocolFallsToOnUnmatched() =
        runQuicTest {
            val accepted = Channel<QuicScope>(Channel.UNLIMITED)
            accepted.send(MockQuicConnection(initialState = QuicConnectionState.Established("not-routed")))
            accepted.close()

            var routed = false
            var unmatched: String? = null
            FakeQuicServer(accepted).connectionsByAlpn(
                "proto-a" to { routed = true },
                onUnmatched = { unmatched = negotiatedAlpn },
            )

            assertEquals("not-routed", unmatched)
            assertTrue(!routed, "unmatched connection must not reach a protocol handler")
        }

    @Test
    fun connectionsByAlpn_rejectsDuplicateRoutes() =
        runQuicTest {
            val accepted = Channel<QuicScope>(Channel.UNLIMITED)
            accepted.close()
            assertFailsWith<IllegalArgumentException> {
                FakeQuicServer(accepted).connectionsByAlpn(
                    "proto-a" to {},
                    "proto-a" to {},
                )
            }
        }

    @Test
    fun connectionsByAlpn_rejectsEmptyRoutes() =
        runQuicTest {
            val accepted = Channel<QuicScope>(Channel.UNLIMITED)
            accepted.close()
            assertFailsWith<IllegalArgumentException> {
                FakeQuicServer(accepted).connectionsByAlpn()
            }
        }
}
