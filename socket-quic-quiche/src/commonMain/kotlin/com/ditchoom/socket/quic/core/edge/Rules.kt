package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.DatagramMaxAge
import com.ditchoom.socket.quic.DatagramOverflow
import com.ditchoom.socket.quic.HandshakePacing
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicCloseLinger
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.RttPrior
import com.ditchoom.socket.quic.core.conn.AgeBound
import com.ditchoom.socket.quic.core.conn.DatagramRule
import com.ditchoom.socket.quic.core.conn.EarlyDataRule
import com.ditchoom.socket.quic.core.conn.HandshakeBound
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.KeepAliveRule
import com.ditchoom.socket.quic.core.conn.Overflow
import com.ditchoom.socket.quic.core.conn.PtoRule
import com.ditchoom.socket.quic.core.conn.quicheHandOff
import com.ditchoom.socket.quic.core.life.CloseRule
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
 * When a graceful close sends its CONNECTION_CLOSE, from [QuicOptions.closeLinger]: waiting for acknowledgement is
 * bounded by the idle timeout, as a peer that acknowledges nothing for that long would have ended the connection.
 */
internal fun closeRuleOf(options: QuicOptions): CloseRule =
    when (val linger = options.closeLinger) {
        is QuicCloseLinger.Immediate -> CloseRule.Immediate
        is QuicCloseLinger.UntilAcknowledged -> CloseRule.UntilAcknowledged(options.idleTimeout.inWholeNanoseconds)
        is QuicCloseLinger.UntilPeerDone -> CloseRule.UntilPeerDone(linger.bound.inWholeNanoseconds)
    }

/** Whether connections of an endpoint carry RFC 9221 datagrams, from [QuicOptions.datagrams]. */
internal fun datagramRuleOf(options: QuicOptions): DatagramRule =
    options.datagrams?.let {
        DatagramRule.On(
            it.recvQueueLen,
            ageBoundOf(it.incomingMaxAge),
            it.sendQueueLen,
            overflowOf(it.overflow),
            ageBoundOf(it.outgoingMaxAge),
        )
    } ?: DatagramRule.Off

private fun ageBoundOf(age: DatagramMaxAge): AgeBound =
    when (age) {
        is DatagramMaxAge.Unbounded -> AgeBound.Unbounded
        is DatagramMaxAge.Within -> AgeBound.Within(age.age.inWholeNanoseconds)
    }

private fun overflowOf(overflow: DatagramOverflow): Overflow =
    when (overflow) {
        is DatagramOverflow.RefuseNew -> Overflow.RefuseNew
        is DatagramOverflow.EvictOldest -> Overflow.EvictOldest
    }

/**
 * The options a core endpoint configures quiche with. The core's own queues are the ones its options bound: quiche's
 * send queue is the short hand-off behind the core's ([quicheHandOff]), and its receive queue holds every datagram one
 * packet can carry, which the core takes out after each packet, so quiche never drops one uncounted.
 */
internal fun QuicOptions.forQuiche(): QuicOptions =
    when (val rule = datagramRuleOf(this)) {
        is DatagramRule.Off -> this
        is DatagramRule.On ->
            copy(
                datagrams =
                    datagrams?.copy(sendQueueLen = rule.quicheHandOff(), recvQueueLen = QUICHE_DATAGRAMS_PER_PACKET),
            )
    }

/** More DATAGRAM frames than one packet can carry: each empty one takes two bytes. */
internal const val QUICHE_DATAGRAMS_PER_PACKET = 1024

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
