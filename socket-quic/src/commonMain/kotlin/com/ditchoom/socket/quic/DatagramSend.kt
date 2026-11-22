@file:OptIn(QuicEngineSpi::class)

package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * What one RFC 9221 datagram send came to, grouped by what the caller does next. No outcome is an exception: a send
 * returns at once with one of these. [Sent] and a channel's [NotSent.QueueFull] are made once, so a sender told again
 * and again that the queue is full allocates nothing.
 *
 * ```
 * when (val r = datagrams.send(frame)) {
 *     DatagramSend.Sent -> Unit
 *     is DatagramSend.NotSent.QueueFull -> r.awaitRoom()             // or drop the frame and move on
 *     is DatagramSend.NotSent.TooLarge -> split(frame, r.maxBytes)
 *     is DatagramSend.NotSent.Unavailable -> stop(r)                  // NotNegotiated, or ConnectionEnded(end)
 * }
 * ```
 */
sealed interface DatagramSend {
    /** The datagram is queued to be sent. A datagram is never retransmitted: it may still be lost on the way. */
    data object Sent : DatagramSend

    /** Nothing was queued. */
    sealed interface NotSent : DatagramSend {
        /**
         * The send queue is at its high-water mark under [DatagramOverflow.RefuseNew]: wait with [awaitRoom] and send
         * again, or drop the datagram. One per channel, made by the engine.
         */
        class QueueFull private constructor(
            private val room: DatagramRoomWait,
        ) : NotSent {
            /**
             * Suspends until the send queue has drained to its low-water mark, or the channel became unavailable;
             * callers waiting together are woken in the order they began to wait. Returns at once, allocating
             * nothing, when the queue is already that low.
             */
            suspend fun awaitRoom() = room.awaitRoom()

            companion object {
                /** The [QueueFull] of the channel whose room [room] waits for. */
                @QuicEngineSpi
                fun of(room: DatagramRoomWait): QueueFull = QueueFull(room)
            }
        }

        /** This datagram can never go as it is: [maxBytes] is the largest payload one datagram carries now. Split it. */
        data class TooLarge(
            val maxBytes: Int,
        ) : NotSent

        /** The channel carries no datagram any more, or never did: stop sending. */
        sealed interface Unavailable : NotSent {
            /** Datagrams are off on this endpoint, or the peer did not advertise them. */
            data object NotNegotiated : Unavailable

            /** The connection ended, or passed its close point, with [end]. */
            data class ConnectionEnded(
                val end: QuicConnectionEnd,
            ) : Unavailable
        }
    }
}

/** How an engine waits for room in a channel's send queue; what [DatagramSend.NotSent.QueueFull.awaitRoom] calls. */
@QuicEngineSpi
fun interface DatagramRoomWait {
    suspend fun awaitRoom()
}

/** What a send queue at its high-water mark does with one more datagram. */
sealed interface DatagramOverflow {
    /** The new datagram is refused with [DatagramSend.NotSent.QueueFull]; those queued stay. */
    data object RefuseNew : DatagramOverflow

    /** The oldest queued datagram not yet being handed to the network is dropped, and counted: the send succeeds. */
    data object EvictOldest : DatagramOverflow
}

/** How long a queued datagram stays worth delivering. */
sealed interface DatagramMaxAge {
    /** A queued datagram waits as long as its queue holds it. */
    data object Unbounded : DatagramMaxAge

    /**
     * A datagram queued for longer than [age] is dropped, and counted, when it reaches the head of its queue. Outgoing,
     * the few datagrams already handed to the QUIC stack for its next packets are past that check; incoming, a datagram
     * a waiting receive takes as it arrives is never old. [age] must be positive.
     */
    data class Within(
        val age: Duration,
    ) : DatagramMaxAge
}
