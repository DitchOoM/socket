package com.ditchoom.socket.testkit.fault

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one fault vocabulary (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §4) — a declarative, deterministic
 * description of what a network does to a stream of transport **units**. A "unit" is the transport's
 * natural quantum: a TCP byte-run, a UDP datagram, a QUIC datagram. The *action set is identical*
 * across all three stacks, which is what lets one schedule drive both the in-process Tier-A pipe and
 * the on-the-wire Tier-C relay (`udp-toxi`) and feel identical to a test author.
 *
 * **Deterministic by construction.** A schedule is an ordered list of [ScheduledFault]s — each pairs
 * a [UnitSelector] (which units) with a [Fault] (what happens) — plus a connection-level
 * [Termination] and a single [seed]. Units are numbered from 0 in send order, per direction. The
 * index selectors ([UnitSelector.Index]/[Every]/[Range]/[From]) are **bit-deterministic**:
 * `drop(nth = k)` drops the *k-th* unit on every run — the Tier-A ⇄ Tier-C parity contract.
 *
 * **One seed, fixed draw order.** All stochastic elements — [Fault.Delay]'s jitter,
 * [UnitSelector.Probabilistic] selection, [Fault.Reorder]'s hold count — are driven by a single
 * `Random(`[seed]`)`, exactly like the Tier-B pipe's `ImpairmentConfig`. The interpreting pipe draws
 * a *fixed number of values per unit in a fixed order* (whether or not a given fault is enabled), so
 * the decision sequence is a pure function of ([seed], unit order) and independent of which faults
 * fire. A schedule with no stochastic elements is fully deterministic regardless of [seed].
 *
 * **No impossible states, no overloaded nullables.** Every alternative is a sealed variant with an
 * exhaustive `when`; every optional sub-parameter is an *identity value* ([Duration.ZERO]), never
 * `null` standing in for "absent". A schedule that expresses nothing is [CLEAN].
 *
 * @property faults the per-unit faults, applied in list order to matching units.
 * @property termination how the connection ends, or [Termination.None].
 * @property seed drives every stochastic element; irrelevant to a purely index-deterministic schedule.
 */
class FaultSchedule internal constructor(
    val faults: List<ScheduledFault>,
    val termination: Termination,
    val seed: Long,
) {
    /** True when this schedule does nothing — no faults and no early termination. */
    val isClean: Boolean get() = faults.isEmpty() && termination is Termination.None

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is FaultSchedule &&
                    faults == other.faults &&
                    termination == other.termination &&
                    seed == other.seed
            )

    override fun hashCode(): Int = 31 * (31 * faults.hashCode() + termination.hashCode()) + seed.hashCode()

    override fun toString(): String = "FaultSchedule(seed=$seed, faults=$faults, termination=$termination)"

    companion object {
        /** Fixed default seed so `reorder`/`drop(p)`/jittered delays are reproducible without one supplied. */
        const val DEFAULT_SEED: Long = 0x5E_ED_C0_DE

        /** The no-op schedule: a clean network. Prefer this over an empty `FaultSchedule { }`. */
        val CLEAN: FaultSchedule = FaultSchedule(emptyList(), Termination.None, DEFAULT_SEED)

        /**
         * Build a schedule with the fault DSL, e.g.
         * ```
         * FaultSchedule(seed = 7) {
         *     drop(nth = 3)
         *     reorder(window = 2)
         *     delay(20.milliseconds)
         * }
         * ```
         * [seed] defaults to [DEFAULT_SEED] — supply your own only when sweeping stochastic realism.
         */
        operator fun invoke(
            seed: Long = DEFAULT_SEED,
            build: FaultScheduleBuilder.() -> Unit,
        ): FaultSchedule = FaultScheduleBuilder(seed).apply(build).build()
    }
}

/** One entry in a [FaultSchedule]: the [fault] applied to every unit the [selector] matches. */
data class ScheduledFault(
    val selector: UnitSelector,
    val fault: Fault,
)

/**
 * What happens to a matched transport unit. Sealed and exhaustive — every impairment the harness can
 * apply to a unit is one of these, so interpreting a schedule is a total `when`. No nullable payloads:
 * an omitted sub-parameter is an identity value (e.g. [Delay.jitter] defaulting to [Duration.ZERO]).
 *
 * Connection-level effects (reset, blackhole) are **not** here — blackhole is just [Drop] over a
 * [UnitSelector.From], and reset is a [Termination]. Keeping unit-faults and connection-events
 * separate is what prevents nonsense like "reset the 3rd byte".
 */
sealed interface Fault {
    /**
     * Hold the unit before delivery. [jitter] is an *additional* bounded random delay in
     * `[0, jitter)` layered on the fixed [by] — [Duration.ZERO] (the default) means none. The jitter
     * value is drawn from the schedule seed as part of the per-unit fixed draw order.
     */
    data class Delay(
        val by: Duration,
        val jitter: Duration = Duration.ZERO,
    ) : Fault

    /** Silently discard the unit. The unit is counted as sent-and-dropped; nothing reaches the peer. */
    data object Drop : Fault

    /**
     * Deliver the unit twice; the duplicate trails the original by [spacing] (default 1 ms, matching
     * the Tier-B pipe). Meaningful for datagram transports (UDP/QUIC); a TCP byte-run duplicate is
     * not expressible on the wire and is rejected by the TCP binding.
     */
    data class Duplicate(
        val spacing: Duration = 1.milliseconds,
    ) : Fault

    /**
     * Flip the bits set in [flipMask] (`0x00..0xFF`) of the byte at [offset], XOR-style — a
     * deterministic single-byte corruption. An [offset] past the unit's length is a no-op on that
     * unit (short units pass through uncorrupted), never an error.
     */
    data class Corrupt(
        val offset: Int,
        val flipMask: Int,
    ) : Fault {
        init {
            require(offset >= 0) { "Corrupt.offset must be >= 0, was $offset" }
            require(flipMask in 0..0xFF) { "Corrupt.flipMask must be a byte in 0..0xFF, was $flipMask" }
        }
    }

    /**
     * Hold this unit up to [maxHoldSlots] extra delivery-slots (a seeded count in `[0, maxHoldSlots]`)
     * so later units can overtake it — deterministic reordering that never stalls a lone in-flight
     * unit. The stream-wide `reorder(window)` DSL expands to this over [UnitSelector.All].
     */
    data class Reorder(
        val maxHoldSlots: Int,
    ) : Fault {
        init {
            require(maxHoldSlots >= 0) { "Reorder.maxHoldSlots must be >= 0, was $maxHoldSlots" }
        }
    }
}

/**
 * Which units a [Fault] applies to. Units are numbered from 0 in send order, per direction. Sealed so
 * selection is exhaustive at the interpretation site; the index-based forms are bit-deterministic (the
 * Tier-A ⇄ Tier-C parity guarantee), [Probabilistic] is realism-only (reproducible in distribution
 * via the schedule seed).
 */
sealed interface UnitSelector {
    /** Exactly the [nth] unit (0-based). The canonical, bit-deterministic selector. */
    data class Index(
        val nth: Int,
    ) : UnitSelector {
        init {
            require(nth >= 0) { "Index.nth must be >= 0, was $nth" }
        }
    }

    /** Every [n]-th unit starting at [offset]: `offset, offset+n, offset+2n, …` ([n] ≥ 1). */
    data class Every(
        val n: Int,
        val offset: Int = 0,
    ) : UnitSelector {
        init {
            require(n >= 1) { "Every.n must be >= 1, was $n" }
            require(offset >= 0) { "Every.offset must be >= 0, was $offset" }
        }
    }

    /** The half-open unit range `[fromInclusive, untilExclusive)`. */
    data class Range(
        val fromInclusive: Int,
        val untilExclusive: Int,
    ) : UnitSelector {
        init {
            require(fromInclusive >= 0) { "Range.fromInclusive must be >= 0, was $fromInclusive" }
            require(untilExclusive >= fromInclusive) {
                "Range.untilExclusive ($untilExclusive) must be >= fromInclusive ($fromInclusive)"
            }
        }
    }

    /** [fromInclusive] and every unit after it — the shape a blackhole uses (`From(k) → Drop`). */
    data class From(
        val fromInclusive: Int,
    ) : UnitSelector {
        init {
            require(fromInclusive >= 0) { "From.fromInclusive must be >= 0, was $fromInclusive" }
        }
    }

    /**
     * Each unit independently with [probability] `p ∈ [0,1]`, drawn from the schedule seed in unit
     * order. Reproducible in distribution, **not** bit-exact across differing unit counts — Tier-A
     * prefers the index selectors.
     */
    data class Probabilistic(
        val probability: Double,
    ) : UnitSelector {
        init {
            require(probability in 0.0..1.0) { "Probabilistic.probability must be in 0.0..1.0, was $probability" }
        }
    }

    /** Every unit. Used by stream-wide impairments (uniform delay, reorder window). */
    data object All : UnitSelector
}

/**
 * How the connection ends. A [Fault] acts on a unit; a [Termination] acts on the whole connection, so
 * they are deliberately different types — you cannot accidentally "reset a datagram".
 */
sealed interface Termination {
    /** The connection ends normally (peer/idle close); the harness injects no early teardown. */
    data object None : Termination

    /**
     * After [count] units have crossed, tear the connection down hard (TCP RST / abortive close).
     * Datagram transports have no connection to reset — the UDP/QUIC bindings reject this and a
     * blackhole (`From(count) → Drop`) is the datagram analogue.
     */
    data class ResetAfterUnits(
        val count: Int,
    ) : Termination {
        init {
            require(count >= 0) { "ResetAfterUnits.count must be >= 0, was $count" }
        }
    }
}

/**
 * DSL for [FaultSchedule.invoke]. Each verb mirrors the RFC §4 action table and appends one
 * [ScheduledFault] (or sets the [Termination]); the two `drop` overloads are distinguished by argument
 * *type* — an `Int` index (deterministic) vs. a `Double` probability (distributional) — never by a
 * nullable flag.
 */
class FaultScheduleBuilder internal constructor(
    private val seed: Long,
) {
    private val faults = mutableListOf<ScheduledFault>()
    private var termination: Termination = Termination.None

    /** Uniform delay on every unit, with optional seeded [jitter] in `[0, jitter)`. */
    fun delay(
        by: Duration,
        jitter: Duration = Duration.ZERO,
    ) {
        faults += ScheduledFault(UnitSelector.All, Fault.Delay(by, jitter))
    }

    /** Drop exactly the [nth] unit — bit-deterministic. */
    fun drop(nth: Int) {
        faults += ScheduledFault(UnitSelector.Index(nth), Fault.Drop)
    }

    /** Drop every [n]-th unit starting at [offset]. */
    fun dropEvery(
        n: Int,
        offset: Int = 0,
    ) {
        faults += ScheduledFault(UnitSelector.Every(n, offset), Fault.Drop)
    }

    /**
     * Drop each unit with [probability] `p` (drawn from the schedule seed) — realism only,
     * reproducible in distribution, not bit-exact. Distinct overload from [drop] `(nth)` by type.
     */
    fun drop(probability: Double) {
        faults += ScheduledFault(UnitSelector.Probabilistic(probability), Fault.Drop)
    }

    /** Deliver the [nth] unit twice, the copy trailing by [spacing]. */
    fun duplicate(
        nth: Int,
        spacing: Duration = 1.milliseconds,
    ) {
        faults += ScheduledFault(UnitSelector.Index(nth), Fault.Duplicate(spacing))
    }

    /** Flip [flipMask] bits of the byte at [offset] in the [nth] unit. */
    fun corrupt(
        nth: Int,
        offset: Int = 0,
        flipMask: Int = 0xFF,
    ) {
        faults += ScheduledFault(UnitSelector.Index(nth), Fault.Corrupt(offset, flipMask))
    }

    /**
     * Stream-wide reordering: every unit is held a seeded number of extra slots in `[0, window]`, so
     * neighbours with unequal holds overtake one another. Deterministic via the schedule seed.
     */
    fun reorder(window: Int) {
        faults += ScheduledFault(UnitSelector.All, Fault.Reorder(window))
    }

    /** Drop every unit from the [nth] onward — total connectivity loss (the datagram blackhole). */
    fun blackholeFrom(nth: Int) {
        faults += ScheduledFault(UnitSelector.From(nth), Fault.Drop)
    }

    /** Tear the connection down hard after [units] units have crossed (TCP only). */
    fun resetAfter(units: Int) {
        termination = Termination.ResetAfterUnits(units)
    }

    internal fun build(): FaultSchedule = FaultSchedule(faults.toList(), termination, seed)
}
