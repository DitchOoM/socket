package com.ditchoom.socket.quic.core.life

/** How far a thrown object reaches. */
internal enum class Tier {
    /** An exception: it ends the connection it was thrown in, and no other. */
    Connection,

    /** An `Error`: it is not contained; the connection ends and the error is reported on its thread. */
    Process,
}

/** The single classification of a thrown object, used by the three catch sites. */
internal object Tiers {
    fun of(thrown: Throwable): Tier =
        when (thrown) {
            is Exception -> Tier.Connection
            else -> Tier.Process
        }
}
