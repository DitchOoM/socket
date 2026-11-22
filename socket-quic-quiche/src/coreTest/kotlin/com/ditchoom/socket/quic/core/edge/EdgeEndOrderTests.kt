package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.HandlerStart
import com.ditchoom.socket.quic.HandshakeRecord
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.core.conn.HandshakeFacts
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import com.ditchoom.socket.quic.core.conn.HeldReads
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The order in which a connection's end reaches its callers. A waiter resumed by the end runs on an unconfined
 * dispatcher here, so it runs inside [EdgeState.ended] at the instant it is woken: whatever it sees is exactly what
 * [EdgeState.ended] had published by then, with no other thread to win or lose a race.
 */
class EdgeEndOrderTests {
    @Test
    fun aParkedAcceptWokenByTheEndSeesTheEndAlreadyPublished() =
        runBlocking {
            val edge = EdgeState(BufferFactory.network().allocate(ALPN), TraceCapture.Off, TicketHome.NotKept, ChainHome.NotKept)
            val accept =
                async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                    try {
                        edge.incoming.receive()
                        Seen.Returned
                    } catch (closed: QuicCloseException) {
                        Seen.Failed(closed.closeReason, edge.state.value)
                    }
                }
            val end = ConnEnd.ByLocal(QuicError.NoError)
            edge.ended(end, HeldReads(emptyMap(), emptySet()), CloseOutcome.NotAsked, HandshakeFate.NotCompleted)
            val seen = assertIs<Seen.Failed>(accept.await())
            assertEquals<QuicCloseReason>(QuicCloseReason.ByLocal(QuicError.NoError), seen.reason)
            assertEquals<QuicConnectionState>(QuicConnectionState.Closed(seen.reason), seen.state)
        }

    @Test
    fun anEndThatCarriesACompletedHandshakeStartsItsHandlerAndKeepsWhatTheHandshakeSettled() {
        val edge = EdgeState(BufferFactory.network().allocate(ALPN), TraceCapture.Off, TicketHome.NotKept, ChainHome.NotKept)
        val facts = HandshakeFacts("test", NOT_OFFERED)
        val end = ConnEnd.ByPeer(QuicError.ApplicationError(7L))
        edge.ended(end, HeldReads(emptyMap(), emptySet()), CloseOutcome.NotAsked, HandshakeFate.Completed(facts))
        assertEquals<HandlerStart>(HandlerStart.Now, edge.start.value, "a server offers a connection whose handshake completed")
        assertEquals("test", assertIs<HandshakeRecord.Settled>(edge.handshake.value).negotiatedAlpn)
    }

    @Test
    fun anEndThatCarriesNoCompletedHandshakeStartsNoHandler() {
        val edge = EdgeState(BufferFactory.network().allocate(ALPN), TraceCapture.Off, TicketHome.NotKept, ChainHome.NotKept)
        val end = ConnEnd.ByPeer(QuicError.ApplicationError(7L))
        edge.ended(end, HeldReads(emptyMap(), emptySet()), CloseOutcome.NotAsked, HandshakeFate.NotCompleted)
        assertEquals<HandlerStart>(HandlerStart.Never, edge.start.value)
    }

    private sealed interface Seen {
        data object Returned : Seen

        class Failed(
            val reason: QuicCloseReason,
            val state: QuicConnectionState,
        ) : Seen
    }

    private companion object {
        const val ALPN = 32
        val NOT_OFFERED = QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered)
    }
}
