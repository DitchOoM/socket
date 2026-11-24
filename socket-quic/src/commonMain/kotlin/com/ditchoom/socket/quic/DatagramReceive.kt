package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer

/**
 * What one RFC 9221 datagram receive came to, grouped as a send's is ([DatagramSend]). No outcome is an exception.
 *
 * ```
 * when (val r = datagrams.receive { batch -> batch.forEach(::handle) }) {
 *     is DatagramReceive.Batch -> Unit                                   // r.count datagrams were handled
 *     DatagramReceive.NotReceived.ReceiverBusy -> Unit                   // another receive of this connection runs
 *     is DatagramReceive.NotReceived.Unavailable -> stop(r)              // NotNegotiated, or ConnectionEnded(end)
 * }
 * ```
 */
sealed interface DatagramReceive<out R> {
    /** [count] datagrams were taken at one resume, every one the connection held then; [value] is what the block made of them. */
    class Batch<out R>(
        val value: R,
        val count: Int,
    ) : DatagramReceive<R> {
        override fun toString(): String = "Batch($count, $value)"
    }

    /** No datagram was received. */
    sealed interface NotReceived : DatagramReceive<Nothing> {
        /** Another receive of this connection is under way: its datagrams have one receiver at a time. */
        data object ReceiverBusy : NotReceived

        /** No datagram will arrive: stop receiving. */
        sealed interface Unavailable : NotReceived {
            /** Datagrams are off on this endpoint, or the peer did not advertise them. */
            data object NotNegotiated : Unavailable

            /** The connection ended, or passed its close point, with [end]. */
            data class ConnectionEnded(
                val end: QuicConnectionEnd,
            ) : Unavailable
        }
    }
}

/**
 * The datagrams one receive took, oldest first. Valid only inside the receive's block: the buffers are the connection's
 * memory, read in place and reused by the next receive, so copy out what outlives the block.
 */
interface QuicDatagramBatch {
    /** How many datagrams the batch holds; at least one. */
    val size: Int

    /** The datagram at [index], from its first byte to its last; throws [IndexOutOfBoundsException] outside `0 until size`. */
    operator fun get(index: Int): ReadBuffer
}

/** [action] on each datagram of the batch, oldest first. */
inline fun QuicDatagramBatch.forEach(action: (ReadBuffer) -> Unit) {
    for (index in 0 until size) action(get(index))
}
