package com.ditchoom.socket.quic.core.life

import kotlin.time.Duration

/** What a handshake that hit its deadline had reached. A timed-out handshake has no duration and no pace. */
internal class HandshakeProgress(
    val waited: Duration,
    val firstSample: RttSample,
    val prior: Duration,
    val link: LinkClass,
    val probes: Int,
    val reached: HandshakeReached,
)

internal sealed interface RttSample {
    /** No acknowledgement has produced a sample yet. */
    data object None : RttSample

    class Sampled(
        val rtt: Duration,
    ) : RttSample
}

internal enum class HandshakeReached { NothingReceived, PeerAnswered, EstablishedUnconfirmed }

/** The first hop's kind, which sets the handshake's RTT prior. */
internal enum class LinkClass { Ethernet, Wifi, Cellular, Satellite, Unknown }
