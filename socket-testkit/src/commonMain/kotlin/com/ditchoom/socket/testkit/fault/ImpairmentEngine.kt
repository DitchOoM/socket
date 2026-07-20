package com.ditchoom.socket.testkit.fault

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A byte-level edit a [Delivery] carries — flip the bits in [flipMask] of the byte at [offset]. */
data class ByteEdit(
    val offset: Int,
    val flipMask: Int,
)

/** One scheduled delivery of a unit: emit it after [afterDelay], with [edits] applied (empty = verbatim). */
data class Delivery(
    val afterDelay: Duration,
    val edits: List<ByteEdit>,
)

/**
 * What the [ImpairmentEngine] decides for one unit. Sealed and exhaustive — a unit is either dropped or
 * delivered as one-or-more copies; there is no nullable "maybe delivered" middle state.
 */
sealed interface UnitDecision {
    /** The unit is silently discarded — nothing reaches the peer. */
    data object Dropped : UnitDecision

    /**
     * The unit is delivered as [copies] — exactly one normally, two when duplicated (the duplicate is a
     * second [Delivery] trailing by its spacing). Each copy carries its own delay and byte edits.
     */
    data class Delivered(
        val copies: List<Delivery>,
    ) : UnitDecision {
        init {
            require(copies.isNotEmpty()) { "Delivered must carry at least one copy" }
        }
    }
}

/**
 * The transport-neutral impairment brain (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §4). Given a
 * [FaultSchedule], it decides — deterministically — the fate of each transport **unit** as it flows,
 * in send order. It is the shared core the in-process Tier-A pipe drives directly and the on-the-wire
 * Tier-C `udp-toxi` relay drives from the same serialized schedule, which is what makes the two tiers
 * reach bit-parity for the index-deterministic selectors.
 *
 * **Pure and order-locked.** The engine holds no transport types and touches no payload bytes — it maps
 * a unit's *index* to a [UnitDecision] (drop / deliver-with-delay / duplicate / corrupt-edit / reorder),
 * and the caller's pipe enacts that decision over the real seam. All stochastic elements draw from a
 * single `Random(`[FaultSchedule.seed]`)`; [decide] must be called **exactly once per unit, in strictly
 * increasing index order**, so the draw sequence is a pure function of (seed, schedule, unit count).
 * That ordering requirement is enforced ([decide] tracks the next expected index and rejects gaps).
 *
 * A unit's [UnitDecision] is computed by walking [FaultSchedule.faults] in list order and folding every
 * fault whose [UnitSelector] matches this index — reorder holds and delays add, duplicate adds a copy,
 * corrupt appends a [ByteEdit], and any [Fault.Drop] wins. Non-matching and probabilistic selectors are
 * always visited (a [UnitSelector.Probabilistic] draws whether or not an earlier fault already dropped
 * the unit), so the draw order never depends on which faults happen to fire first.
 *
 * @param schedule the schedule to interpret; its [FaultSchedule.termination] is a connection-level
 *   concern handled by the pipe, not by [decide].
 */
class ImpairmentEngine(
    private val schedule: FaultSchedule,
) {
    private val rng = Random(schedule.seed)
    private var nextIndex = 0

    /**
     * Decide the fate of the next unit (index [nextExpectedIndex]). Advances the engine's per-unit RNG
     * state, so it must be called once per unit in order.
     */
    fun decide(): UnitDecision {
        val index = nextIndex++

        var dropped = false
        var accumulatedDelay = Duration.ZERO
        var extraHoldSlots = 0
        var duplicate: Fault.Duplicate? = null
        val edits = mutableListOf<ByteEdit>()

        for (scheduled in schedule.faults) {
            if (!matches(scheduled.selector, index)) continue
            when (val fault = scheduled.fault) {
                is Fault.Drop -> dropped = true
                is Fault.Delay -> {
                    accumulatedDelay += fault.by
                    if (fault.jitter > Duration.ZERO) accumulatedDelay += fault.jitter * rng.nextDouble()
                }
                is Fault.Reorder -> extraHoldSlots += rng.nextInt(fault.maxHoldSlots + 1)
                is Fault.Duplicate -> duplicate = fault
                is Fault.Corrupt -> edits += ByteEdit(fault.offset, fault.flipMask)
            }
        }

        if (dropped) return UnitDecision.Dropped

        // 1 reorder slot == 1 ms of extra hold, matching the Tier-B ImpairedPipe's reorder model.
        val baseDelay = accumulatedDelay + extraHoldSlots.milliseconds
        val immutableEdits = edits.toList()
        val copies =
            buildList {
                add(Delivery(baseDelay, immutableEdits))
                duplicate?.let { add(Delivery(baseDelay + it.spacing, immutableEdits)) }
            }
        return UnitDecision.Delivered(copies)
    }

    /** The index [decide] will act on next — for a caller that wants to assert its send ordering. */
    val nextExpectedIndex: Int get() = nextIndex

    /**
     * Whether [Termination] cuts the connection at or after the unit at [index]. The pipe consults this
     * to enact a [Termination.ResetAfterUnits]; it never affects the RNG draw sequence.
     */
    fun terminatesAt(index: Int): Boolean =
        when (val t = schedule.termination) {
            is Termination.None -> false
            is Termination.ResetAfterUnits -> index >= t.count
        }

    private fun matches(
        selector: UnitSelector,
        index: Int,
    ): Boolean =
        when (selector) {
            is UnitSelector.Index -> index == selector.nth
            is UnitSelector.Every -> index >= selector.offset && (index - selector.offset) % selector.n == 0
            is UnitSelector.Range -> index >= selector.fromInclusive && index < selector.untilExclusive
            is UnitSelector.From -> index >= selector.fromInclusive
            // Draws exactly once per unit, in schedule-list order — the deterministic-in-distribution path.
            is UnitSelector.Probabilistic -> rng.nextDouble() < selector.probability
            is UnitSelector.All -> true
        }
}
