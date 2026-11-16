package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
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
            val edge = EdgeState(BufferFactory.network().allocate(ALPN), TraceCapture.Off)
            val accept =
                async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                    try {
                        edge.incoming.receive()
                        Seen.Returned
                    } catch (closed: QuicCloseException) {
                        Seen.Failed(closed.closeReason, edge.state.value)
                    }
                }
            edge.ended(ConnEnd.ByLocal(QuicError.NoError))
            val seen = assertIs<Seen.Failed>(accept.await())
            assertEquals<QuicCloseReason>(QuicCloseReason.ByLocal(QuicError.NoError), seen.reason)
            assertEquals<QuicConnectionState>(QuicConnectionState.Closed(seen.reason), seen.state)
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
    }
}
