package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One local endpoint of a client: its address as quiche reads it ([key]), and the receive info for datagrams that
 * arrive on it. [made] says whose its sockaddr and receive info are, and so who frees them; [prior] is the round trip
 * quiche assumes for the path from here until it has a sample.
 */
internal class LocalAddr(
    val key: PathKey,
    val recvInfo: QuicheRecvInfo,
    val made: Made,
    val prior: RttPrior,
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
 * declared lost, never when the response is, and can stop asking altogether on a path it already gave up once. A
 * challenge that could not be answered before the abandonment, one new-path round trip ([newRttNanos]) later, is not
 * asked: the abandonment falls due in its place.
 */
internal class Cadence private constructor(
    val challengeDue: Nanos,
    val abandonAt: Nanos,
    private val newRttNanos: Long,
) {
    /** The same validation, next challenged one [CHALLENGE_INTERVAL_NANOS] after [now]. */
    fun challenged(now: Nanos): Cadence = of(now, abandonAt, newRttNanos)

    companion object {
        fun of(
            now: Nanos,
            abandonAt: Nanos,
            newRttNanos: Long,
        ): Cadence {
            val next = now + CHALLENGE_INTERVAL_NANOS
            return Cadence(if (next.value + newRttNanos > abandonAt.value) abandonAt else next, abandonAt, newRttNanos)
        }
    }
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

/**
 * This move on another usable network: what failed on the network before says nothing of this one, so a stranded
 * endpoint is probed again at [now] and the move backs off afresh.
 */
internal fun Move.onNewNetwork(now: Nanos): Move =
    when (this) {
        is Move.Stranded -> Move.Stranded(from, at, why, Retry(now, 0), id)
        is Move.Probing -> Move.Probing(from, to, 0, cadence)
        is Move.Settled -> this
    }

/** What a client asked to move answers. */
internal enum class MoveAnswer {
    /** The new endpoint's path is being validated, or has no spare id to be. */
    Following,

    /** The peer forbade migration: the connection cannot follow its socket. */
    Refused,

    /** The peer gave no spare id and is silent, so none can come: the connection cannot follow its socket. */
    NoUsablePath,
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

    /** A stranded endpoint has no spare id and the peer is silent, so none can come: the connection ends. */
    NoUsablePath,
}

/** Hands [each] every endpoint this move holds. */
internal fun Move.locals(each: (LocalAddr) -> Unit) {
    when (this) {
        is Move.Settled -> each(on.local)
        is Move.Probing -> {
            each(from.local)
            each(to.local)
        }
        is Move.Stranded -> {
            each(from.local)
            each(at)
        }
    }
}

/** The endpoint the socket leaves from while this move stands. */
internal fun Move.socketAt(): LocalAddr =
    when (this) {
        is Move.Settled -> on.local
        is Move.Probing -> to.local
        is Move.Stranded -> at
    }

/**
 * The endpoint a datagram that arrives is read on: the socket's. While a move waits for a spare id, quiche has no path
 * for the socket's endpoint, so the datagram is read on the old path, which can carry the id the peer sends.
 */
internal fun Move.receivesAt(): LocalAddr =
    when (this) {
        is Move.Stranded -> if (why == Unprobed.NoSpareId) from.local else at
        is Move.Settled, is Move.Probing -> socketAt()
    }

/** This stranded move after another probe found no path: retried at the next backoff step. */
internal fun Move.Stranded.again(
    q: QuicheConnRef,
    now: Nanos,
): Move.Stranded = Move.Stranded(from, at, why, q.retryAt(now, retry.failed + 1), id)

/**
 * When a stranded endpoint is probed again after [failed] probes of its move found no path: one PTO of the path the
 * connection still has, doubled for each failure up to [MAX_DOUBLINGS] times, so a path that stays dark is asked less
 * often until the idle timeout ends the connection.
 */
internal fun QuicheConnRef.retryAt(
    now: Nanos,
    failed: Int,
): Retry = Retry(now + (activeBasePto() shl minOf(failed, MAX_DOUBLINGS)), failed)

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
 * times the larger of the two paths' PTOs (RFC 9000 §8.2.4). Each PTO is RFC 9002 §6.2.1's base PTO, without the
 * doubling for each expiry: after an outage that doubling is the outage's length, not the path's. The path being
 * validated is the one that is neither active nor validated; until it has an RTT sample its PTO and round trip are
 * those of kInitialRtt. Reads quiche's statistics, which allocates: once per validation.
 */
internal fun QuicheConnRef.cadenceFrom(now: Nanos): Cadence {
    var activePto = NEW_PATH_PTO_NANOS
    var sampledPto = 0L
    var sampledRtt = 0L
    var index = 0L
    while (index < MOST_PATHS) {
        val stats = api.connPathStats(handle, index++) ?: break
        if (stats.active) {
            activePto = basePto(stats)
        } else if (stats.validationState != QUICHE_PATH_VALIDATED && stats.minRtt > Duration.ZERO) {
            sampledPto = maxOf(sampledPto, basePto(stats))
            sampledRtt = maxOf(sampledRtt, stats.rtt.inWholeNanoseconds)
        }
    }
    val newPto = if (sampledPto > 0L) sampledPto else NEW_PATH_PTO_NANOS
    val newRtt = if (sampledRtt > 0L) sampledRtt else K_INITIAL_RTT_NANOS
    return Cadence.of(now, now + ABANDON_PTOS * maxOf(activePto, newPto), newRtt)
}

/** The base PTO of the path the connection is active on, or kInitialRtt's when quiche reports none. */
internal fun QuicheConnRef.activeBasePto(): Long {
    var index = 0L
    while (index < MOST_PATHS) {
        val stats = api.connPathStats(handle, index++) ?: break
        if (stats.active) return basePto(stats)
    }
    return NEW_PATH_PTO_NANOS
}

/** RFC 9002 §6.2.1's PTO from the path's RTT estimate, before any doubling. */
private fun basePto(stats: QuicPathStats): Long =
    (stats.rtt + maxOf(stats.rttvar * RTTVAR_FACTOR, GRANULARITY) + MAX_ACK_DELAY).inWholeNanoseconds

/** How many of quiche's paths are read: its own limit on a client's concurrent paths is far below it. */
internal const val MOST_PATHS = 16L

/** `PathState::Validated`, as `quiche_path_stats.validation_state` reports it. */
private const val QUICHE_PATH_VALIDATED = 3L

private const val MAX_DOUBLINGS = 4
private const val RTTVAR_FACTOR = 4
internal const val ABANDON_PTOS = 3
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
