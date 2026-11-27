package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * How a connection ended, exhaustively. Each engine reports exactly one of these per connection, and
 * [QuicCloseException.end] carries it. [closeReason] is the coarser [QuicCloseReason] the connection's state reports.
 *
 * Every variant but [ReasonOnly] is reported by the run-to-completion core; [ReasonOnly] is reported by an engine
 * that records only the close reason.
 */
sealed interface QuicConnectionEnd {
    /** The peer closed the connection with [error]. */
    data class ByPeer(
        val error: QuicError,
    ) : QuicConnectionEnd

    /** The application closed the connection with [error]. */
    data class ByLocal(
        val error: QuicError,
    ) : QuicConnectionEnd

    /** The idle timeout passed with the network usable. */
    data object IdleTimeout : QuicConnectionEnd

    /** The handshake did not complete within its bound; [progress] says what it had reached. */
    data class HandshakeTimedOut(
        val progress: QuicHandshakeProgress,
    ) : QuicConnectionEnd

    /**
     * The handshake did not complete within its bound, and the network refused every datagram it was handed: nothing was
     * sent, as distinct from [HandshakeTimedOut], where the peer was sent to and never answered. [refusal] is the last refusal.
     */
    data class HandshakeSendRefused(
        val progress: QuicHandshakeProgress,
        val refusal: QuicSendRefusal,
    ) : QuicConnectionEnd

    /**
     * The handshake ended before its bound because the operating system withholds network access from this app:
     * [denial] says which access, how that is known, and its remedy.
     */
    data class AccessDenied(
        val denial: QuicAccessDenial,
    ) : QuicConnectionEnd

    /** The connection's UDP socket failed as [fault] says. */
    class SocketFailed(
        val fault: QuicSocketFault,
    ) : QuicConnectionEnd

    /** Work inside the connection threw [thrown]. */
    class ProcessingFailed(
        val thrown: Throwable,
    ) : QuicConnectionEnd

    /** A caller's thread that was inside the connection threw [thrown]. */
    class CallerFailed(
        val thrown: Throwable,
    ) : QuicConnectionEnd

    /** The connection's loop thread threw [thrown]. */
    class LoopFailed(
        val thrown: Throwable,
    ) : QuicConnectionEnd

    /** The owner of the connection's loop stopped it. */
    data object LoopStopped : QuicConnectionEnd

    /** The connection's server stopped, and the connection's CONNECTION_CLOSE was sent. */
    data object ServerClosed : QuicConnectionEnd

    /** The connection's server stopped at its drain bound before the connection's CONNECTION_CLOSE was sent. */
    data object ServerClosedUnsettled : QuicConnectionEnd

    /** The QUIC stack closed the connection and named neither side. */
    data object NoErrorRecorded : QuicConnectionEnd

    /** The connection was freed before any end was recorded. */
    data object Released : QuicConnectionEnd

    /** The idle timeout passed while the network monitor reported no usable network. */
    data object NetworkLost : QuicConnectionEnd

    /** The local address the connection's socket was bound to went away, and the connection could not move. */
    data object LocalAddressGone : QuicConnectionEnd

    /** No path the connection could send on was left. */
    data object NoUsablePath : QuicConnectionEnd

    /**
     * The server rejected the client's 0-RTT for [reason] and changed what the early bytes relied on, as [change] says:
     * nothing written in the [QuicResumption.ResumeWithEarlyData] block reached the server, and the client ended the
     * connection rather than replay it (RFC 9001 §4.6.2). Connect again; the block runs again on that connection.
     */
    data class EarlyDataRejected(
        val reason: QuicEarlyDataRejection,
        val change: QuicEarlyDataChange,
    ) : QuicConnectionEnd

    /** An engine that records only the close reason ended the connection with [reason]. */
    data class ReasonOnly(
        val reason: QuicCloseReason,
    ) : QuicConnectionEnd
}

/** How a [QuicConnectionEnd] reads as the connection state's [QuicCloseReason]. */
val QuicConnectionEnd.closeReason: QuicCloseReason
    get() =
        when (this) {
            is QuicConnectionEnd.ByPeer -> QuicCloseReason.ByPeer(error)
            is QuicConnectionEnd.ByLocal -> QuicCloseReason.ByLocal(error)
            is QuicConnectionEnd.IdleTimeout -> QuicCloseReason.ByLocal(QuicError.IdleTimeout)
            is QuicConnectionEnd.HandshakeTimedOut -> QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(progress.waited))
            is QuicConnectionEnd.HandshakeSendRefused -> QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(progress.waited))
            is QuicConnectionEnd.ProcessingFailed -> QuicCloseReason.ByLocal(QuicError.ProcessingFailed)
            is QuicConnectionEnd.ServerClosed -> QuicCloseReason.ByLocal(QuicError.NoError)
            is QuicConnectionEnd.ServerClosedUnsettled -> QuicCloseReason.ByLocal(QuicError.ServerStoppedBeforeCloseSent)
            is QuicConnectionEnd.NoUsablePath -> QuicCloseReason.ByLocal(QuicError.NoViablePath)
            is QuicConnectionEnd.EarlyDataRejected -> QuicCloseReason.ByLocal(QuicError.NoError)
            is QuicConnectionEnd.ReasonOnly -> reason
            is QuicConnectionEnd.AccessDenied,
            is QuicConnectionEnd.SocketFailed, is QuicConnectionEnd.CallerFailed, is QuicConnectionEnd.LoopFailed,
            is QuicConnectionEnd.LoopStopped, is QuicConnectionEnd.NoErrorRecorded, is QuicConnectionEnd.Released,
            is QuicConnectionEnd.NetworkLost, is QuicConnectionEnd.LocalAddressGone,
            -> QuicCloseReason.Unspecified
        }

/**
 * What a handshake that hit its bound had reached: how long it [waited], its [firstSample] of round-trip time,
 * the [prior] round-trip estimate it started from on a [link], the [probes] it sent, and whether the peer answered.
 */
data class QuicHandshakeProgress(
    val waited: Duration,
    val firstSample: QuicRttSample,
    val prior: Duration,
    val link: LinkClass,
    val probes: Int,
    val reached: QuicHandshakeReached,
)

/** A round-trip sample, or none. */
sealed interface QuicRttSample {
    /** No acknowledgement has produced a sample. */
    data object None : QuicRttSample

    data class Sampled(
        val rtt: Duration,
    ) : QuicRttSample
}

/** Whether anything came back from the peer during a handshake. */
enum class QuicHandshakeReached { NothingReceived, PeerAnswered }

/** Why the network refused a datagram. */
sealed interface QuicSendRefusal {
    /** The datagram was larger than the path allows. */
    data object MessageTooLarge : QuicSendRefusal

    data object NetworkUnreachable : QuicSendRefusal

    data object HostUnreachable : QuicSendRefusal

    data object PortUnreachable : QuicSendRefusal

    data object NoBufferSpace : QuicSendRefusal

    /** The operating system denied the send, as a firewall or local-network privacy does. */
    data object PermissionDenied : QuicSendRefusal

    /** An error the library does not name; [thrown] is the platform's own. */
    class Unnamed(
        val thrown: Throwable,
    ) : QuicSendRefusal
}

/** How a connection's UDP socket failed. */
sealed interface QuicSocketFault {
    /** The socket was closed under the connection. */
    data object Closed : QuicSocketFault

    /** The socket reported closed after an error the library does not name; [thrown] is that error. */
    class Unnamed(
        val thrown: Throwable,
    ) : QuicSocketFault

    /** The network changed and no socket could be opened again on the same local address and port. */
    class ReopenRefused(
        val thrown: Throwable,
    ) : QuicSocketFault

    /** The local address changed and no socket could be opened at the new one. */
    class MoveRefused(
        val thrown: Throwable,
    ) : QuicSocketFault

    /** No socket could be opened at the address asked for; [thrown] is why: the address is in use, or not this host's. */
    class OpenRefused(
        val thrown: Throwable,
    ) : QuicSocketFault

    /** [count] unnamed errors in a row with no datagram taken in between; [thrown] is the last. */
    class Repeated(
        val count: Int,
        val thrown: Throwable,
    ) : QuicSocketFault
}
