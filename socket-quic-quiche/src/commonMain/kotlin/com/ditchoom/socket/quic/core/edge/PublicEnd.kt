package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicHandshakeProgress
import com.ditchoom.socket.quic.QuicHandshakeReached
import com.ditchoom.socket.quic.QuicRttSample
import com.ditchoom.socket.quic.QuicSocketFault
import com.ditchoom.socket.quic.core.life.HandshakeProgress
import com.ditchoom.socket.quic.core.life.HandshakeReached
import com.ditchoom.socket.quic.core.life.RttSample
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
