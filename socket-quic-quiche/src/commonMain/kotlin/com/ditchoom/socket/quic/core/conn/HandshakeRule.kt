package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig

/** How a connection's handshake retransmits before it is confirmed. */
internal sealed interface PtoRule {
    /** quiche's RFC 9002 PTO from its own 333 ms initial RTT, uncapped. */
    data object Rfc9002 : PtoRule

    /**
     * quiche starts from [priorNanos]; every PTO interval is capped by a ceiling that is [firstCeilingNanos] and grows
     * 3× per PTO expiry until the handshake is confirmed.
     */
    class Adaptive(
        val priorNanos: Long,
        val firstCeilingNanos: Long,
    ) : PtoRule
}

/** How long a handshake may take before the connection ends. */
internal sealed interface HandshakeBound {
    /** Only the idle timeout ends a handshake. */
    data object UntilClosed : HandshakeBound

    class Bounded(
        val nanos: Long,
    ) : HandshakeBound
}

/** The handshake rule every connection of an endpoint is built with; immutable and shared. */
internal class HandshakeRule(
    val pto: PtoRule,
    val bound: HandshakeBound,
    /** The link class the prior was chosen for, or the one the network monitor reported. */
    val link: LinkClass,
) {
    /** The RTT assumed before the first sample. */
    fun priorNanos(): Long =
        when (pto) {
            is PtoRule.Rfc9002 -> RFC9002_PRIOR_NANOS
            is PtoRule.Adaptive -> pto.priorNanos
        }

    /** Gives [config] the rule's initial RTT; RFC 9002 keeps quiche's own. */
    fun applyTo(
        api: QuicheApi,
        config: QuicheConfig,
    ) {
        when (pto) {
            is PtoRule.Rfc9002 -> Unit
            is PtoRule.Adaptive -> api.configSetInitialRtt(config, pto.priorNanos)
        }
    }

    companion object {
        /** RFC 9002 §6.2.2's initial RTT. */
        const val RFC9002_PRIOR_NANOS = 333_000_000L

        /**
         * The adaptive rule from a [priorNanos] and a [floorNanos]: the prior is raised to a third of the floor, as
         * quiche's first PTO is three times it, and the first ceiling is the larger of the floor and that first PTO.
         */
        fun adaptive(
            priorNanos: Long,
            floorNanos: Long,
            bound: HandshakeBound,
            link: LinkClass,
        ): HandshakeRule {
            val prior = maxOf(priorNanos, floorNanos / 3)
            return HandshakeRule(PtoRule.Adaptive(prior, maxOf(floorNanos, 3 * prior)), bound, link)
        }

        /** The prior of [link]: the path RTT a first hop of that class plans for. */
        fun priorOf(link: LinkClass): Long =
            when (link) {
                LinkClass.Ethernet -> 40_000_000L
                LinkClass.Wifi -> 80_000_000L
                LinkClass.Cellular -> 150_000_000L
                LinkClass.Satellite -> 700_000_000L
                LinkClass.Unknown -> RFC9002_PRIOR_NANOS
            }
    }
}
