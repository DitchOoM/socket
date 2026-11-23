package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.Thrown

/**
 * How a connection ended. Every failure a caller receives carries one, as a required field; nothing reads
 * `Throwable.cause`. [Thrown] and [SocketFault] live in `udp`, below `life`, because a socket's end is one of these.
 */
internal sealed interface ConnEnd {
    class ByPeer(
        val error: QuicError,
    ) : ConnEnd

    /** `close()` by the application. */
    class ByLocal(
        val error: QuicError,
    ) : ConnEnd

    data object IdleTimeout : ConnEnd

    class HandshakeTimedOut(
        val progress: HandshakeProgress,
    ) : ConnEnd

    /** The handshake hit its bound with every datagram refused by the network: nothing was sent. [refusal] is the last. */
    class HandshakeSendRefused(
        val progress: HandshakeProgress,
        val refusal: SendRefusal,
    ) : ConnEnd

    /** A client's handshake ended at once because the system withholds network access from the app, as [denial] says. */
    class AccessDenied(
        val denial: QuicAccessDenial,
    ) : ConnEnd

    class SocketFailed(
        val fault: SocketFault,
    ) : ConnEnd

    /** Work threw inside this connection. */
    class ProcessingFailed(
        val thrown: Thrown,
    ) : ConnEnd

    /** An `Error` on a caller's thread that was inside the connection. */
    class CallerFailed(
        val thrown: Thrown,
    ) : ConnEnd

    class LoopFailed(
        val thrown: Thrown,
    ) : ConnEnd

    /** Its owner stopped the loop. */
    data object LoopStopped : ConnEnd

    data object ServerClosed : ConnEnd

    /** Its server stopped at the drain bound with this connection's CONNECTION_CLOSE not yet out of the thread inside. */
    data object ServerClosedUnsettled : ConnEnd

    /** quiche closed and named neither side. */
    data object NoErrorRecorded : ConnEnd

    /** Freed before any end was recorded. */
    data object Released : ConnEnd

    /** The idle timeout passed while the network monitor reported no usable network. */
    data object NetworkLost : ConnEnd

    /** The local address the connection's socket is bound to went away, and the connection could not move with it. */
    data object LocalAddressGone : ConnEnd

    /** No path the connection could send on was left: every one it had failed, or none it moved to was validated. */
    data object NoUsablePath : ConnEnd
}
