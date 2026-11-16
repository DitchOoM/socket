package com.ditchoom.socket.quic

/** The longest PTO interval quiche may wait while the handshake is unconfirmed. */
sealed interface PtoCeiling {
    /** quiche's own PTO, uncapped. */
    data object None : PtoCeiling

    class At(
        val nanos: Long,
    ) : PtoCeiling
}

/** [ceiling] as the C seam's argument: nanoseconds, zero for none. */
internal fun ptoCeilingNanos(ceiling: PtoCeiling): Long =
    when (ceiling) {
        is PtoCeiling.None -> 0L
        is PtoCeiling.At -> ceiling.nanos
    }
