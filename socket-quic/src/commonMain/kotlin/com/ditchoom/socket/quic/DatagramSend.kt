package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * What one RFC 9221 datagram send came to. No outcome is an exception: a send returns at once with one of these.
 * [Sent] and a channel's [QueueFull] are made once, so a sender told again and again that the queue is full allocates
 * nothing.
 *
 * ```
 * when (val r = datagrams.send(frame)) {
 *     DatagramSend.Sent -> Unit
 *     is DatagramSend.QueueFull -> r.awaitRoom() // or drop the frame and move on
 *     is DatagramSend.TooLarge -> split(frame, r.maxBytes)
 *     DatagramSend.NotNegotiated -> useAStream(frame)
 *     is DatagramSend.ConnectionEnded -> stop(r.end)
 * }
 * ```
 */
sealed interface DatagramSend {
    /** The datagram is queued to be sent. A datagram is never retransmitted: it may still be lost on the way. */
    data object Sent : DatagramSend

    /**
     * The send queue is at its high-water mark under [DatagramOverflow.RefuseNew]: nothing was queued. Dropping the
     * datagram is a valid answer; [awaitRoom] waits for room instead.
     */
    abstract class QueueFull : DatagramSend {
        /**
         * Suspends until the send queue has drained to its low-water mark, or the connection ended; callers waiting
         * together are woken in the order they began to wait. Returns at once, allocating nothing, when the queue is
         * already that low.
         */
        abstract suspend fun awaitRoom()
    }

    /** The datagram does not fit the path as it stands; [maxBytes] is the largest payload one datagram carries now. */
    data class TooLarge(
        val maxBytes: Int,
    ) : DatagramSend

    /** The connection carries no datagram: they are off on this endpoint, or the peer did not advertise them. */
    data object NotNegotiated : DatagramSend

    /** The connection ended with [end]: nothing more is sent. */
    data class ConnectionEnded(
        val end: QuicConnectionEnd,
    ) : DatagramSend
}

/** What a send queue at its high-water mark does with one more datagram. */
sealed interface DatagramOverflow {
    /** The new datagram is refused with [DatagramSend.QueueFull]; those queued stay. */
    data object RefuseNew : DatagramOverflow

    /** The oldest queued datagram is dropped, and counted, to make room: the send succeeds. */
    data object EvictOldest : DatagramOverflow
}

/** How long a queued datagram stays worth delivering. */
sealed interface DatagramMaxAge {
    /** A queued datagram waits as long as its queue holds it. */
    data object Unbounded : DatagramMaxAge

    /** A datagram queued for longer than [age] is dropped, and counted, when it reaches the head of its queue. */
    data class Within(
        val age: Duration,
    ) : DatagramMaxAge
}
