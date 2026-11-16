package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.NetworkErrorDomain
import com.ditchoom.socket.quic.NetworkRefusal
import com.ditchoom.socket.quic.NwConnection
import com.ditchoom.socket.quic.NwCreated
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.ready.PollHandle
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.udp.NwIo
import com.ditchoom.socket.quic.core.udp.NwLife
import com.ditchoom.socket.quic.core.udp.NwSettled
import com.ditchoom.socket.quic.core.udp.NwVerdict
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** On Apple a client's datagrams ride Network.framework: no POSIX client socket, so the radio it needs comes up. */
internal actual val clientRideOfThisPlatform: ClientRide = ClientRide.NetworkFramework(NwConnector())

internal actual class NwConnector

/** A ready Network.framework connection and its state. */
internal actual class NwSocket(
    val connection: NwConnection,
    val life: NwLife,
    val peer: PathKey,
)

/**
 * Starts a connection to [host]:[port] and suspends until Network.framework says it is ready or refuses it. A caller's
 * cancellation, its timeout included, cancels the connection.
 */
internal actual suspend fun NwConnector.connect(
    host: String,
    port: Int,
): StackSocket.Network {
    val connection =
        when (val created = NwConnection.create(host, port)) {
            is NwCreated.Created -> created.connection
            is NwCreated.Refused ->
                raise(Raised.NetworkRefused(NetworkRefusal(NetworkErrorDomain.Unreported, 0)), QuicCloseCircumstance.HandshakeFailed)
        }
    val life = NwLife()
    val verdict =
        suspendCancellableCoroutine { waiting ->
            connection.start { state, domain, code, path ->
                when (val settled = life.changed(state, domain, code, path)) {
                    is NwSettled.First -> waiting.resume(settled.verdict)
                    is NwSettled.Not -> Unit
                }
            }
            waiting.invokeOnCancellation {
                life.cancelled()
                connection.cancel()
            }
        }
    return when (verdict) {
        is NwVerdict.Ready -> {
            val wildcard = PathKey(4, 0, 0L, 0L)
            val peer = connection.remote(wildcard)
            StackSocket.Network(NwSocket(connection, life, peer), connection.local(peer))
        }
        is NwVerdict.Refused -> {
            connection.cancel()
            raise(Raised.NetworkRefused(verdict.refusal), QuicCloseCircumstance.HandshakeFailed)
        }
    }
}

internal actual fun nwSocketIo(
    socket: StackSocket.Network,
    wake: () -> Unit,
): SocketIo {
    val io = NwIo(socket.socket.connection, socket.socket.life, socket.socket.peer, wake)
    io.start()
    return SocketIo(io, SocketRef { PollHandle.NotPolled })
}
