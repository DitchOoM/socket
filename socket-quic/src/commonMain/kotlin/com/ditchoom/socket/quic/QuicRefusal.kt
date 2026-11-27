package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.NetworkKind
import kotlin.time.Duration

/**
 * Why a connect or a serve was refused before any socket opened: what it asked for cannot be done here, so it fails
 * with the reason instead of running without it.
 */
sealed interface QuicRefusal {
    /** [StandbyLink.KeepCellularReady]: no standby link is held; use [StandbyLink.OnDemand]. */
    data object StandbyLinkNotHeld : QuicRefusal

    /**
     * A [DatagramOptions.sendQueueLen] of [sendQueueLen], below the [minimum] a send queue holds: one datagram handed to
     * the QUIC stack, one waiting for it, and one in the queue a send copies into.
     */
    data class DatagramSendQueueTooShort(
        val sendQueueLen: Int,
        val minimum: Int,
    ) : QuicRefusal

    /** A [DatagramMaxAge.Within] of [age], which is not positive: every datagram would be dropped. */
    data class DatagramMaxAgeNotPositive(
        val age: Duration,
    ) : QuicRefusal

    /** A [MigrationPolicy.Pinned] connection cannot be pinned to [radio], for [reason]. */
    data class RadioNotPinnable(
        val radio: NetworkKind.Radio,
        val reason: PinnedRadioRefusal,
    ) : QuicRefusal

    /**
     * The datagram channel lent to this connect or serve already carries a QUIC connection or listener: a channel
     * carries one QUIC role at a time, and is free again once that one has ended and let go of it.
     */
    data object ChannelInUse : QuicRefusal

    /** This target has no QUIC. */
    data object NoQuicOnThisPlatform : QuicRefusal
}

/** A connect or a serve refused for [refusal], raised before any socket opens. */
class QuicRefusedException(
    val refusal: QuicRefusal,
) : UnsupportedOperationException(refusal.toString())

/** The refusal of a connect that cannot be pinned to [radio], for [reason]; the same at every candidate endpoint. */
fun notPinnable(
    radio: NetworkKind.Radio,
    reason: PinnedRadioRefusal,
): QuicRefusedException = QuicRefusedException(QuicRefusal.RadioNotPinnable(radio, reason))
