package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.observe.FlightKind
import kotlin.time.Duration

/**
 * A connection as the thread inside it saw it when a stall report asked: each party a stalled caller can be waiting
 * on is a field. Built inside, handed out immutable.
 */
internal class StallView(
    /** quiche's own next timeout. */
    val quiche: QuicheWait,
    /** The loop deadline the connection armed, which runs quiche's timeout and every offered wake. */
    val deadline: ArmedDeadline,
    val liveStreams: Int,
    /** Opens parked on the peer's stream limit. */
    val opensWaiting: Int,
    val stats: QuicStatsRead,
    val flight: FlightTail,
)

internal sealed interface QuicheWait {
    data object Unarmed : QuicheWait

    class Due(
        val after: Duration,
    ) : QuicheWait
}

internal sealed interface ArmedDeadline {
    /** The connection holds no deadline on its loop: nothing will serve it unless a datagram or work arrives. */
    data object Unarmed : ArmedDeadline

    /** Due [after] from the report; a negative value is a deadline the loop owes and has not run. */
    class Due(
        val after: Duration,
    ) : ArmedDeadline
}

/** The newest record of the connection's flight recording. */
internal sealed interface FlightTail {
    data object Empty : FlightTail

    class Last(
        val kind: FlightKind,
        val ago: Duration,
        val held: Int,
        val overwritten: Long,
    ) : FlightTail
}
