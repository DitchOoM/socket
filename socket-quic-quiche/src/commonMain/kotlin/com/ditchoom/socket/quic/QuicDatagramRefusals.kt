package com.ditchoom.socket.quic

/** Why a core datagram call was refused. */
internal sealed interface DatagramRefusal {
    /** Datagrams are off here, or the peer never advertised support for them. */
    data object NotNegotiated : DatagramRefusal

    /** The send queue is full; nothing was queued. */
    data object QueueFull : DatagramRefusal

    /** Another receive of the connection's datagrams is under way: they have one receiver at a time. */
    data object ReceiverBusy : DatagramRefusal

    /** quiche refused it with [code]. */
    class Quiche(
        val code: Int,
    ) : DatagramRefusal {
        override fun toString() = "Quiche(code=$code)"
    }
}

/** What a datagram call raises when the connection cannot carry it: [refusal] says why, and so does its message. */
internal class QuicDatagramRefused(
    val refusal: DatagramRefusal,
) : IllegalStateException("datagram refused: $refusal")

/** A datagram of [size] bytes does not fit the path as it stands, which carries [maxBytes]. */
internal class QuicDatagramTooLarge(
    val size: Int,
    val maxBytes: Int,
) : IllegalArgumentException("datagram of $size bytes is larger than the $maxBytes the path carries")
