package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.Nanos
import kotlin.time.Duration.Companion.milliseconds

/**
 * One local endpoint of a client: its address as quiche reads it ([key]), and the receive info for datagrams that
 * arrive on it. [made] says whose its sockaddr and receive info are, and so who frees them.
 */
internal class LocalAddr(
    val key: PathKey,
    val recvInfo: QuicheRecvInfo,
    val made: Made,
) {
    val info = RecvFrom.Info(recvInfo)
}

/** Who made a [LocalAddr]'s sockaddr and receive info. */
internal sealed interface Made {
    /** The connect's own, freed with the path the connection was opened on. */
    data object ByEdge : Made

    class Here(
        val sockAddr: EncodedSockAddr,
    ) : Made
}

/** A local endpoint and the destination connection id quiche linked to its path: the two never separate. */
internal class Linked(
    val local: LocalAddr,
    val dcidSeq: Long,
)

/** When a stranded client probes its endpoint again, and how many probes of the move have found no path. */
internal class Retry(
    val due: Nanos,
    val failed: Int,
)

/** Whether a stranded endpoint's path holds a destination id. */
internal sealed interface LinkedId {
    data object None : LinkedId

    class Seq(
        val seq: Long,
    ) : LinkedId
}

/** Why a moved socket's endpoint has no path being validated. */
internal enum class Unprobed { NoSpareId, ValidationFailed, MigrationRefused }

/** Where a client's path stands. Every destination id it holds is in exactly one [Linked]; the step that drops one retires it. */
internal sealed interface Move {
    /** The connection's one path leaves from [on]. */
    class Settled(
        val on: Linked,
    ) : Move

    /** The socket moved to [to], whose path is being validated; quiche's active path is still [from]. */
    class Probing(
        val from: Linked,
        val to: Linked,
        /** The probes of this move that found no path. */
        val failed: Int,
    ) : Move

    /** The socket moved to [at], whose path is not being validated; quiche's active path is still [from]. */
    class Stranded(
        val from: Linked,
        val at: LocalAddr,
        val why: Unprobed,
        val retry: Retry,
        /** The id quiche linked to [at]'s path, kept so the path can be probed again. */
        val id: LinkedId,
    ) : Move
}

/** What a client asked to move answers. */
internal enum class MoveAnswer {
    /** The new endpoint's path is being validated, or has no spare id to be. */
    Following,

    /** The peer forbade migration: the connection cannot follow its socket. */
    Refused,
}

/** What one serve's look at the paths found. */
internal enum class PathStep {
    Steady,
    Migrated,

    /** Quiche gave up validating the probed path, or closed it. */
    ProbeFailed,

    /** A stranded endpoint was probed again. */
    Reprobed,
}

/**
 * When a stranded endpoint is probed again after [failed] probes of its move found no path: one PTO of the path the
 * connection still has, doubled for each failure up to [MAX_DOUBLINGS] times, so a path that stays dark is asked less
 * often until the idle timeout ends the connection.
 */
internal fun QuicheConnRef.retryAt(
    now: Nanos,
    failed: Int,
): Retry {
    val pto =
        when (val stats = api.connPathStats(handle, 0L)) {
            null -> INITIAL_PTO_NANOS
            else -> (stats.rtt + maxOf(stats.rttvar * RTTVAR_FACTOR, GRANULARITY) + MAX_ACK_DELAY).inWholeNanoseconds
        }
    return Retry(now + (pto shl minOf(failed, MAX_DOUBLINGS)), failed)
}

private const val MAX_DOUBLINGS = 4
private const val RTTVAR_FACTOR = 4
private val GRANULARITY = 1.milliseconds
private val MAX_ACK_DELAY = 25.milliseconds
private val INITIAL_PTO_NANOS = 1_000_000_000L
