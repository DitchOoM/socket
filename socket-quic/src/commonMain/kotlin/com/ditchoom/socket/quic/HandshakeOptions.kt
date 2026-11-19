package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the handshake retransmits before it is confirmed. A probe timeout (PTO) re-sends the handshake flight when no
 * acknowledgement arrived in time.
 */
sealed interface HandshakePacing {
    /** RFC 9002: a 333 ms initial RTT and the sample-derived PTO, doubled per expiry, with no ceiling. */
    data object Rfc9002 : HandshakePacing

    /**
     * The first PTO follows [prior], never below [floor]; every PTO interval is capped by a ceiling that starts at
     * `max(floor, 3 × prior)` and grows 3× per PTO expiry until the handshake is confirmed. quiche's own smaller PTO
     * always wins, so the cap only shortens a wait an inflated first RTT sample would lengthen.
     */
    data class Adaptive(
        val prior: RttPrior = RttPrior.FromLink,
        val floor: Duration = 25.milliseconds,
    ) : HandshakePacing
}

/** The round-trip time assumed before the first sample. */
sealed interface RttPrior {
    /** The prior of the link class of the default network at connect; a server uses RFC 9002's 333 ms. */
    data object FromLink : RttPrior

    /** The prior of [link], whatever the network monitor reports. */
    data class OfLink(
        val link: LinkClass,
    ) : RttPrior

    /** A path RTT the caller knows, such as the last connection's to the same peer. */
    data class Known(
        val rtt: Duration,
    ) : RttPrior
}

/** The first hop's kind, which sets the handshake's RTT prior: 40, 80, 150, 700 and 333 ms in declaration order. */
enum class LinkClass { Ethernet, Wifi, Cellular, Satellite, Unknown }

/** How long a handshake may take before the connection ends. */
sealed interface HandshakePolicy {
    /** The connection ends with a handshake timeout once [bound] has passed since the connect without confirmation. */
    data class Bounded(
        val bound: Duration,
    ) : HandshakePolicy

    /** Only the idle timeout ends a handshake. */
    data object UntilClosed : HandshakePolicy
}
