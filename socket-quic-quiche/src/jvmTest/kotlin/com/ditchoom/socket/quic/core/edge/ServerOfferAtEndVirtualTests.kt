package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Which server connections the server offers, on a real core client and server on virtual time: one whose handshake
 * completed is offered however soon it ended after, even when it ended before anyone accepted it; one whose client's
 * close arrives in the serve that completes its handshake, or before, is never offered.
 */
class ServerOfferAtEndVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aConnectionWhoseClientClosedInTheServeThatCompletedItsHandshakeIsNeverOffered() {
        val seen = FirstCompletion()
        VirtualPair.with(options, start = PairStart.Started, serverApi = { seen.over(it) }) { pair ->
            pair.hold(End.Server)
            pair.runUntil { pair.heldFrom(End.Server) > 0 }
            pair.hold(End.Client)
            pair.release(End.Server)
            pair.runUntil { pair.client.connection.state.value is QuicConnectionState.Established }
            val finished = pair.heldFrom(End.Client)
            pair.client.connection.closeNow(QuicError.NoError)
            pair.runUntil { pair.heldFrom(End.Client) > finished }
            pair.release(End.Client)
            pair.runFor(pair.oneWay * 6)
            assertEquals(Completion.WhileClosing, seen.completion, "the client's close arrived with its last handshake flight")
            assertNull(withTimeoutOrNull(OFFER_WAIT) { pair.server.accept() }, "a connection closed in its handshake's serve was offered")
        }
    }

    @Test
    fun aConnectionWhoseHandshakeCompletedIsOfferedEndedWhenItsClientClosedBeforeTheAccept() {
        VirtualPair.with(options) { pair ->
            pair.client.connection.closeNow(QuicError.ApplicationError(APP_CODE))
            pair.runFor(pair.oneWay * 6)
            val server = pair.accept()
            val ended = assertIs<EdgeEnd.Ended>(server.end(), "the client's close had landed before the accept")
            assertIs<ConnEnd.ByPeer>(ended.end)
            assertIs<HandshakeFate.Completed>(ended.handshake)
            assertEquals("test", server.negotiatedAlpn)
        }
    }

    @Test
    fun aConnectionWhoseClientClosedBeforeItsHandshakeCompletedIsNeverOffered() {
        val seen = FirstCompletion()
        VirtualPair.with(options, start = PairStart.Started, serverApi = { seen.over(it) }) { pair ->
            pair.hold(End.Server)
            pair.runUntil { pair.heldFrom(End.Server) > 0 }
            pair.client.connection.closeNow(QuicError.NoError)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            pair.runFor(pair.oneWay * 6)
            assertEquals(Completion.Never, seen.completion, "the server's handshake never completed")
            assertNull(withTimeoutOrNull(OFFER_WAIT) { pair.server.accept() }, "a connection whose handshake never completed was offered")
        }
    }

    /** Whether the server's quiche reported its handshake complete, and whether it had already closed by then. */
    private enum class Completion { Never, WhileOpen, WhileClosing }

    /** The server's api: notes how things stood the first time quiche said its handshake was complete. */
    private class FirstCompletion {
        var completion = Completion.Never
            private set

        fun over(inner: QuicheApi): QuicheApi =
            object : QuicheApi by inner {
                override fun connIsEstablished(conn: QuicheConn): Boolean {
                    val established = inner.connIsEstablished(conn)
                    if (established && completion == Completion.Never) {
                        val closing = inner.connIsDraining(conn) || inner.connIsClosed(conn)
                        completion = if (closing) Completion.WhileClosing else Completion.WhileOpen
                    }
                    return established
                }
            }
    }

    private companion object {
        const val APP_CODE = 0x2AL

        /** The offer runs on the server's handler pool, on the wall clock: an offer due would be made well within this. */
        val OFFER_WAIT = 2.seconds
    }
}
