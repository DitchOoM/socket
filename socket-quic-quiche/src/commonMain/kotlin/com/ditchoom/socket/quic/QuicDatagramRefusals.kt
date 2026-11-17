package com.ditchoom.socket.quic

/** Why a core datagram call was refused. */
internal sealed interface DatagramRefusal {
    /** Datagrams are off here, or the peer never advertised support for them. */
    data object NotNegotiated : DatagramRefusal

    /** quiche's send queue is full; nothing was queued. */
    data object QueueFull : DatagramRefusal

    /** quiche refused it with [code]. */
    class Quiche(
        val code: Int,
    ) : DatagramRefusal
}

/** What a datagram call raises when the connection cannot carry it: [refusal] says why. */
internal class QuicDatagramRefused(
    val refusal: DatagramRefusal,
) : IllegalStateException()

/** A datagram of [size] bytes does not fit the path as it stands, which carries [maxBytes]. */
internal class QuicDatagramTooLarge(
    val size: Int,
    val maxBytes: Int,
) : IllegalArgumentException()
