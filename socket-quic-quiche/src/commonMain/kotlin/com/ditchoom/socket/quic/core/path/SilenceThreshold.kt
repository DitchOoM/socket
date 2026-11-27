package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.ACTIVE_PATH_INDEX_SHIFT
import com.ditchoom.socket.quic.ACTIVE_PATH_PROBES_MASK
import com.ditchoom.socket.quic.NO_ACTIVE_PATH
import com.ditchoom.socket.quic.core.time.Span
import kotlin.jvm.JvmInline

/**
 * When the active path has stopped answering: [expiries] consecutive PTO expiries unanswered over at least [floor]
 * from the first of them, or a run unanswered for [patience] whatever its count. The count decides on slow paths, the
 * floor on fast ones (RFC 9002's PTO cannot fall below quiche's 25 ms max_ack_delay, so fifteen PTOs can be 0.4 s), and
 * the patience on a path whose PTO is still the unsampled 1 s of RFC 9002 §6.2.2. [of] builds one; production holds
 * [SHIPPED].
 */
internal class SilenceThreshold private constructor(
    val expiries: Long,
    val floor: Span,
    val patience: Span,
) {
    /** Whether a run of [unanswered] expiries, the first [silentFor] ago, means the path stopped answering. */
    fun isMetBy(
        unanswered: Long,
        silentFor: Span,
    ): Boolean {
        if (unanswered < 1) return false
        if (silentFor >= patience) return true
        return unanswered >= expiries && silentFor >= floor
    }

    /** Whether ack-eliciting data unanswered for [unansweredFor] means the path stopped answering: the patience alone. */
    fun isMetByUnansweredSends(unansweredFor: Span): Boolean = unansweredFor >= patience

    companion object {
        /** Four expiries (Chromium's path-degrading count) over 2 s, or 4 s unanswered. */
        val SHIPPED = SilenceThreshold(4L, Span(2_000_000_000L), Span(4_000_000_000L))

        fun of(
            expiries: Long,
            floor: Span,
            patience: Span,
        ): ThresholdOf =
            when {
                expiries < 1 -> ThresholdOf.Invalid(ThresholdProblem.NotEnoughExpiries)
                floor.nanos <= 0L -> ThresholdOf.Invalid(ThresholdProblem.NoTimeFloor)
                patience <= floor -> ThresholdOf.Invalid(ThresholdProblem.PatienceUnderFloor)
                else -> ThresholdOf.Valid(SilenceThreshold(expiries, floor, patience))
            }
    }
}

/** What [SilenceThreshold.of] builds. */
internal sealed interface ThresholdOf {
    class Valid(
        val threshold: SilenceThreshold,
    ) : ThresholdOf

    class Invalid(
        val problem: ThresholdProblem,
    ) : ThresholdOf
}

/** Why a [SilenceThreshold] cannot mean anything. */
internal enum class ThresholdProblem {
    /** Fewer than one expiry: every idle connection would read as silent. */
    NotEnoughExpiries,

    /** No time floor: the count alone, which calls a fast path's blip silence. */
    NoTimeFloor,

    /** A patience at or under the floor, which decides every run before the floor can refuse a blip. */
    PatienceUnderFloor,
}

/** quiche's active path, read without allocating: its index in quiche's path table and its PTO expiries, or none. */
@JvmInline
internal value class ActiveProbes(
    val bits: Long,
) {
    inline fun <R> fold(
        none: () -> R,
        read: (index: Long, probes: Long) -> R,
    ): R = if (bits == NO_ACTIVE_PATH) none() else read(bits ushr ACTIVE_PATH_INDEX_SHIFT, bits and ACTIVE_PATH_PROBES_MASK)
}
