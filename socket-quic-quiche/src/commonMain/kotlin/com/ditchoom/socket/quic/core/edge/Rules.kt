package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.HandshakePacing
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicCloseLinger
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.RttPrior
import com.ditchoom.socket.quic.core.conn.DatagramRule
import com.ditchoom.socket.quic.core.conn.EarlyDataRule
import com.ditchoom.socket.quic.core.conn.HandshakeBound
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.KeepAliveRule
import com.ditchoom.socket.quic.core.conn.PtoRule
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind

/** The handshake rule of a client whose default network's first hop is [observed]. */
internal fun clientHandshakeRule(
    options: QuicOptions,
    observed: LinkClass,
): HandshakeRule = handshakeRule(options, observed, HandshakeRule.priorOf(observed))

/** The handshake rule of a server: it cannot know a client's link, so its prior is RFC 9002's unless one is known. */
internal fun serverHandshakeRule(options: QuicOptions): HandshakeRule =
    handshakeRule(options, LinkClass.Unknown, HandshakeRule.RFC9002_PRIOR_NANOS)

/**
 * What a close with NO_ERROR asks for, from [QuicOptions.closeLinger]: waiting for acknowledgement is limited to
 * [CloseChoice.DEFAULT_LIMIT_NANOS], waiting for the peer to its bound.
 */
internal fun closeChoiceOf(options: QuicOptions): CloseChoice =
    when (val linger = options.closeLinger) {
        is QuicCloseLinger.Immediate -> CloseChoice.Immediate(QuicError.NoError)
        is QuicCloseLinger.UntilAcknowledged -> CloseChoice.Graceful(CloseChoice.DEFAULT_LIMIT_NANOS, DrainUntil.Acknowledged)
        is QuicCloseLinger.UntilPeerDone -> CloseChoice.Graceful(linger.bound.inWholeNanoseconds, DrainUntil.PeerDone)
    }

/** Whether connections of an endpoint carry RFC 9221 datagrams, from [QuicOptions.datagrams]. */
internal fun datagramRuleOf(options: QuicOptions): DatagramRule =
    options.datagrams?.let { DatagramRule.On(it.recvQueueLen) } ?: DatagramRule.Off

/** Whether idle connections of an endpoint are pinged, from [QuicOptions.keepAliveInterval]. */
internal fun keepAliveRuleOf(options: QuicOptions): KeepAliveRule =
    options.keepAliveInterval?.let { KeepAliveRule.Every(it.inWholeNanoseconds) } ?: KeepAliveRule.Off

/** Whether a server accepts 0-RTT, from [QuicOptions.enableEarlyData]. */
internal fun earlyDataRuleOf(options: QuicOptions): EarlyDataRule =
    if (options.enableEarlyData) EarlyDataRule.Accepted else EarlyDataRule.Refused

/** The link class of a network the monitor identified; a VPN, another kind or no identity is [LinkClass.Unknown]. */
internal fun linkClassOf(id: NetworkId): LinkClass =
    when (id) {
        is NetworkId.Unidentified -> LinkClass.Unknown
        is NetworkId.KindOnly -> linkClassOf(id.kind)
        is NetworkId.Link -> linkClassOf(id.kind)
    }

private fun linkClassOf(kind: NetworkKind): LinkClass =
    when (kind) {
        is NetworkKind.Ethernet -> LinkClass.Ethernet
        is NetworkKind.Wifi -> LinkClass.Wifi
        is NetworkKind.Cellular -> LinkClass.Cellular
        is NetworkKind.Vpn, is NetworkKind.Other -> LinkClass.Unknown
    }

private fun handshakeRule(
    options: QuicOptions,
    observed: LinkClass,
    observedPriorNanos: Long,
): HandshakeRule {
    val bound =
        when (val policy = options.handshakePolicy) {
            is HandshakePolicy.Bounded -> HandshakeBound.Bounded(policy.bound.inWholeNanoseconds)
            is HandshakePolicy.UntilClosed -> HandshakeBound.UntilClosed
        }
    return when (val pacing = options.handshakePacing) {
        is HandshakePacing.Rfc9002 -> HandshakeRule(PtoRule.Rfc9002, bound, observed)
        is HandshakePacing.Adaptive -> {
            val floor = pacing.floor.inWholeNanoseconds
            when (val prior = pacing.prior) {
                is RttPrior.FromLink -> HandshakeRule.adaptive(observedPriorNanos, floor, bound, observed)
                is RttPrior.OfLink -> HandshakeRule.adaptive(HandshakeRule.priorOf(prior.link), floor, bound, prior.link)
                is RttPrior.Known -> HandshakeRule.adaptive(prior.rtt.inWholeNanoseconds, floor, bound, observed)
            }
        }
    }
}
