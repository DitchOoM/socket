package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicHandshakeProgress
import com.ditchoom.socket.quic.QuicHandshakeReached
import com.ditchoom.socket.quic.QuicRttSample
import com.ditchoom.socket.quic.QuicSendRefusal
import com.ditchoom.socket.quic.QuicSocketFault
import com.ditchoom.socket.quic.closeReason
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.HandshakeProgress
import com.ditchoom.socket.quic.core.life.HandshakeReached
import com.ditchoom.socket.quic.core.life.RttSample
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.SocketFault

/** The public mirror of what a timed-out handshake had reached. */
internal fun publicProgressOf(progress: HandshakeProgress): QuicHandshakeProgress =
    QuicHandshakeProgress(
        waited = progress.waited,
        firstSample =
            when (val sample = progress.firstSample) {
                is RttSample.None -> QuicRttSample.None
                is RttSample.Sampled -> QuicRttSample.Sampled(sample.rtt)
            },
        prior = progress.prior,
        link = progress.link,
        probes = progress.probes,
        reached =
            when (progress.reached) {
                HandshakeReached.NothingReceived -> QuicHandshakeReached.NothingReceived
                HandshakeReached.PeerAnswered -> QuicHandshakeReached.PeerAnswered
            },
    )

/** The public mirror of how a connection's socket failed. */
internal fun publicFaultOf(fault: SocketFault): QuicSocketFault =
    when (fault) {
        is SocketFault.Closed -> QuicSocketFault.Closed
        is SocketFault.Unnamed -> QuicSocketFault.Unnamed(fault.thrown.throwable)
        is SocketFault.ReopenRefused -> QuicSocketFault.ReopenRefused(fault.thrown.throwable)
        is SocketFault.MoveRefused -> QuicSocketFault.MoveRefused(fault.thrown.throwable)
        is SocketFault.Repeated -> QuicSocketFault.Repeated(fault.count, fault.thrown.throwable)
    }

/** The public mirror of why the network refused a datagram. */
internal fun publicRefusalOf(refusal: SendRefusal): QuicSendRefusal =
    when (refusal) {
        is SendRefusal.TooLarge -> QuicSendRefusal.MessageTooLarge
        is SendRefusal.NetworkUnreachable -> QuicSendRefusal.NetworkUnreachable
        is SendRefusal.HostUnreachable -> QuicSendRefusal.HostUnreachable
        is SendRefusal.PortUnreachable -> QuicSendRefusal.PortUnreachable
        is SendRefusal.NoBufferSpace -> QuicSendRefusal.NoBufferSpace
        is SendRefusal.PermissionDenied -> QuicSendRefusal.PermissionDenied
        is SendRefusal.Unnamed -> QuicSendRefusal.Unnamed(refusal.thrown.throwable)
    }

/** How [end] reads in the public close reason: the reason of its [publicEndOf]. */
internal fun closeReasonOf(end: ConnEnd): QuicCloseReason = publicEndOf(end).closeReason

/** The one mapping from how a connection ended to what callers are told: each [ConnEnd] to its [QuicConnectionEnd]. */
internal fun publicEndOf(end: ConnEnd): QuicConnectionEnd =
    when (end) {
        is ConnEnd.ByPeer -> QuicConnectionEnd.ByPeer(end.error)
        is ConnEnd.ByLocal -> QuicConnectionEnd.ByLocal(end.error)
        is ConnEnd.IdleTimeout -> QuicConnectionEnd.IdleTimeout
        is ConnEnd.HandshakeTimedOut -> QuicConnectionEnd.HandshakeTimedOut(publicProgressOf(end.progress))
        is ConnEnd.HandshakeSendRefused ->
            QuicConnectionEnd.HandshakeSendRefused(publicProgressOf(end.progress), publicRefusalOf(end.refusal))
        is ConnEnd.AccessDenied -> QuicConnectionEnd.AccessDenied(end.denial)
        is ConnEnd.SocketFailed -> QuicConnectionEnd.SocketFailed(publicFaultOf(end.fault))
        is ConnEnd.ProcessingFailed -> QuicConnectionEnd.ProcessingFailed(end.thrown.throwable)
        is ConnEnd.CallerFailed -> QuicConnectionEnd.CallerFailed(end.thrown.throwable)
        is ConnEnd.LoopFailed -> QuicConnectionEnd.LoopFailed(end.thrown.throwable)
        is ConnEnd.LoopStopped -> QuicConnectionEnd.LoopStopped
        is ConnEnd.ServerClosed -> QuicConnectionEnd.ServerClosed
        is ConnEnd.ServerClosedUnsettled -> QuicConnectionEnd.ServerClosedUnsettled
        is ConnEnd.NoErrorRecorded -> QuicConnectionEnd.NoErrorRecorded
        is ConnEnd.Released -> QuicConnectionEnd.Released
        is ConnEnd.NetworkLost -> QuicConnectionEnd.NetworkLost
        is ConnEnd.LocalAddressGone -> QuicConnectionEnd.LocalAddressGone
        is ConnEnd.NoUsablePath -> QuicConnectionEnd.NoUsablePath
    }

/** The exception that reports [end] to a caller who was [during] something, for channels that are closed with one. */
internal fun endedWith(
    end: ConnEnd,
    during: QuicCloseCircumstance,
): Throwable = closeOf(end, during)
