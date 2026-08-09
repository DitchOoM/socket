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
        // Default empty = "offer not reported", which is what makes the routing-coverage check opt-in:
        // the tests below that exercise pure routing keep it empty so no route table is rejected.
        override val alpnProtocols: List<String> = emptyList(),
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

    /**
     * The listener's offer is the authority: a protocol in [QuicServer.alpnProtocols] with no route
     * can only ever surface as a connection falling through to `onUnmatched`, so it is rejected at
     * call time — before the accept loop starts — and the message names the offending protocol.
     */
    @Test
    fun connectionsByAlpn_rejectsRouteTableMissingAnOfferedProtocol() =
        runQuicTest {
            val accepted = Channel<QuicScope>(Channel.UNLIMITED)
            accepted.close()
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    FakeQuicServer(accepted, alpnProtocols = listOf("h3", "my-proto")).connectionsByAlpn(
                        "h3" to {},
                    )
                }
            assertTrue(
                failure.message!!.contains("my-proto"),
                "the unrouted protocol must be named, got: ${failure.message}",
            )
        }

    @Test
    fun connectionsByAlpn_acceptsRouteTableCoveringTheOffer() =
        runQuicTest {
            val accepted = Channel<QuicScope>(Channel.UNLIMITED)
            accepted.send(MockQuicConnection(initialState = QuicConnectionState.Established("h3")))
            accepted.close()

            // Extra routes beyond the offer are dead but harmless (nothing can negotiate them), so
            // only the offer→route direction is enforced.
            val handled = mutableListOf<String>()
            FakeQuicServer(accepted, alpnProtocols = listOf("h3")).connectionsByAlpn(
                "h3" to { handled += negotiatedAlpn },
                "never-offered" to { handled += "unreachable" },
            )

            assertEquals(listOf("h3"), handled)
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
