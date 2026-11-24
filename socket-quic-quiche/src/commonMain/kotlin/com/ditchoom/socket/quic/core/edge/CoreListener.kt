package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicAccept
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A [CoreServer] as the public [QuicListener]: each accepted connection in its public shape. */
internal class CoreListener(
    private val server: CoreServer,
) : QuicListener {
    override val port: Int get() = server.port

    override suspend fun accept(): QuicAccept =
        when (val next = next()) {
            is Offered.Ready -> QuicAccept.Accepted(next.connection)
            is Offered.NeverEstablished, is Offered.Closed -> QuicAccept.Closed
        }

    /** The next connection whose handshake completed, or [Offered.Closed] once the server stopped. */
    private suspend fun next(): Offered {
        while (true) {
            val next = server.next()
            if (next.isClosed) return Offered.Closed
            when (val offered = next.getOrThrow().offered()) {
                is Offered.Ready, is Offered.Closed -> return offered
                is Offered.NeverEstablished -> Unit
            }
        }
    }

    override suspend fun handleConnections(handler: suspend (QuicConnection) -> Unit) =
        coroutineScope {
            while (true) {
                when (val accepted = next()) {
                    is Offered.Closed, is Offered.NeverEstablished -> break
                    is Offered.Ready -> launch(Dispatchers.Default) { accepted.connection.asBlock(handler) }
                }
            }
        }

    override suspend fun close() = server.close()
}

/** What an offered server connection came to once its handshake was decided. */
private sealed interface Offered {
    class Ready(
        val connection: CoreQuicConnection,
    ) : Offered

    /** Offered at its client's 0-RTT, it ended before its handshake completed. */
    data object NeverEstablished : Offered

    /** The server stopped: nothing more is offered. */
    data object Closed : Offered
}

/** This connection in its public shape once its handshake completed, the connection ended since or not. */
private suspend fun CoreConnection.offered(): Offered {
    state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }
    return when (val end = end()) {
        is EdgeEnd.Open -> Offered.Ready(public())
        is EdgeEnd.Ended ->
            when (end.handshake) {
                is HandshakeFate.Completed -> Offered.Ready(public())
                is HandshakeFate.NotCompleted -> Offered.NeverEstablished
            }
    }
}
