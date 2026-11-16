package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError
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

    /** quiche closed and named neither side. */
    data object NoErrorRecorded : ConnEnd

    /** Freed before any end was recorded. */
    data object Released : ConnEnd

    /** The network monitor reported no usable network for longer than the grace a flap is given. */
    data object NetworkLost : ConnEnd

    /** The network came back without the local address the connection's socket is bound to: that is migration. */
    data object LocalAddressGone : ConnEnd
}
