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

/**
 * The timers of one validation of a path: when it is next asked for a fresh PATH_CHALLENGE, and when the validation is
 * abandoned (RFC 9000 §8.2.4) if no PATH_RESPONSE came. Quiche resends a challenge only when the packet carrying it is
 * declared lost, never when the response is, and can stop asking altogether on a path it already gave up once.
 */
internal class Cadence(
    val challengeDue: Nanos,
    val abandonAt: Nanos,
) {
    /** The same validation, next challenged one [CHALLENGE_INTERVAL_NANOS] after [now]. */
    fun challenged(now: Nanos): Cadence = Cadence(now + CHALLENGE_INTERVAL_NANOS, abandonAt)
}

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
        val cadence: Cadence,
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

    /** The path being validated was asked for a fresh PATH_CHALLENGE. */
    Challenged,
}

/**
 * When a stranded endpoint is probed again after [failed] probes of its move found no path: one PTO of the path the
 * connection still has, doubled for each failure up to [MAX_DOUBLINGS] times, so a path that stays dark is asked less
 * often until the idle timeout ends the connection.
 */
internal fun QuicheConnRef.retryAt(
    now: Nanos,
    failed: Int,
): Retry = Retry(now + (currentPto() shl minOf(failed, MAX_DOUBLINGS)), failed)

/**
 * When a path whose validation failed [failed] times is probed again: one [CHALLENGE_INTERVAL_NANOS] after the first
 * failure, doubled for each further one up to [MAX_DOUBLINGS] times. Timed by the new path, which has no RTT sample,
 * never by the old path's estimate, which a dead link inflates.
 */
internal fun validationRetryAt(
    now: Nanos,
    failed: Int,
): Retry = Retry(now + (CHALLENGE_INTERVAL_NANOS shl minOf(failed - 1, MAX_DOUBLINGS)), failed)

/**
 * The timers of a validation starting at [now]: a fresh challenge every [CHALLENGE_INTERVAL_NANOS], abandoned at three
 * times the larger of the current path's PTO and the new path's (RFC 9000 §8.2.4).
 */
internal fun QuicheConnRef.cadenceFrom(now: Nanos): Cadence =
    Cadence(now + CHALLENGE_INTERVAL_NANOS, now + ABANDON_PTOS * maxOf(currentPto(), NEW_PATH_PTO_NANOS))

/** RFC 9002 §6.2.1's PTO of the path the connection still has, from its RTT estimate. */
private fun QuicheConnRef.currentPto(): Long =
    when (val stats = api.connPathStats(handle, 0L)) {
        null -> NEW_PATH_PTO_NANOS
        else -> (stats.rtt + maxOf(stats.rttvar * RTTVAR_FACTOR, GRANULARITY) + MAX_ACK_DELAY).inWholeNanoseconds
    }

private const val MAX_DOUBLINGS = 4
private const val RTTVAR_FACTOR = 4
private const val ABANDON_PTOS = 3
private val GRANULARITY = 1.milliseconds
private val MAX_ACK_DELAY = 25.milliseconds

/** RFC 9002 §6.2.2's kInitialRtt: the RTT of a path with no sample, such as one being validated. */
private const val K_INITIAL_RTT_NANOS = 333_000_000L

/**
 * How often a path being validated is challenged afresh: the PTO of a path with no RTT sample in the Initial space,
 * `kInitialRtt + 4 × kInitialRtt / 2`, as RFC 9000 §8.2.1 probes a new path no more often than an Initial is sent.
 */
internal const val CHALLENGE_INTERVAL_NANOS = K_INITIAL_RTT_NANOS + RTTVAR_FACTOR * (K_INITIAL_RTT_NANOS / 2)

/** The PTO of a new path with no RTT sample in the application space: [CHALLENGE_INTERVAL_NANOS] plus the ack delay. */
private val NEW_PATH_PTO_NANOS = CHALLENGE_INTERVAL_NANOS + MAX_ACK_DELAY.inWholeNanoseconds
