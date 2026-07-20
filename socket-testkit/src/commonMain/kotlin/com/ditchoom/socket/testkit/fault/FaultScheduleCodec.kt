package com.ditchoom.socket.testkit.fault

import kotlin.time.Duration.Companion.nanoseconds

/**
 * Human-readable, deterministic text codec for a [FaultSchedule] — the wire form the `udp-toxi`
 * control plane pushes over HTTP so the Tier-A pipe and the Tier-C relay interpret the *same*
 * schedule (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §5, §11.2). It is the schedule analogue of the
 * neutral trace line grammar: line-oriented, versioned, space-delimited, and git-diffable, so a
 * captured schedule reads the same whether it lands in a fixture, an HTTP body, or a review diff.
 *
 * **Grammar (`faultschedule v2`).** One directive per line; blank lines and everything after a `#`
 * are ignored. Order within the document is preserved for the `fault` lines (schedule order is
 * load-bearing — the [ImpairmentEngine] folds faults in list order).
 *
 * ```
 * faultschedule v2
 * seed <long>                                  # exactly once; the schedule's single RNG seed
 * fault <selector...> <fault...>               # zero or more, in application order
 * reset <count>                                # at most once; omitted ⇒ Termination.None
 * ```
 *
 * Selector tokens (fixed arity) ⇄ [UnitSelector]:
 * `all` · `index <nth>` · `every <n> <offset>` · `range <from> <until>` · `from <nth>` · `prob <p>`.
 *
 * Fault tokens (fixed arity) ⇄ [Fault], durations as whole nanoseconds:
 * `delay <byNanos> <jitterNanos>` · `drop` · `duplicate <spacingNanos>` ·
 * `corrupt <offset> <flipMask>` · `reorder <maxHoldSlots>`.
 *
 * **Exact inverse.** `decode(encode(s)) == s` for every schedule (asserted in the round-trip tests).
 * Decoding re-runs every sealed variant's construction-time `require`, so a malformed or
 * illegal-state document fails loudly at [decode] rather than producing a nonsense schedule — the
 * same no-impossible-states guarantee the builder gives.
 */
object FaultScheduleCodec {
    private const val HEADER = "faultschedule"
    private const val VERSION = "v2"

    /** Serialize [schedule] to the `faultschedule v2` document. */
    fun encode(schedule: FaultSchedule): String =
        buildString {
            append(HEADER).append(' ').append(VERSION).append('\n')
            append("seed ").append(schedule.seed).append('\n')
            for (sf in schedule.faults) {
                append("fault ")
                    .append(encodeSelector(sf.selector))
                    .append(' ')
                    .append(encodeFault(sf.fault))
                    .append('\n')
            }
            when (val t = schedule.termination) {
                is Termination.None -> {}
                is Termination.ResetAfterUnits -> append("reset ").append(t.count).append('\n')
            }
        }

    /**
     * Parse a `faultschedule v2` document back into a [FaultSchedule]. Throws
     * [IllegalArgumentException] on a missing/unknown header, an unknown directive, a bad token
     * count, or any value that violates a variant's invariant.
     */
    fun decode(text: String): FaultSchedule {
        var seed: Long? = null
        var termination: Termination = Termination.None
        var sawHeader = false
        val faults = mutableListOf<ScheduledFault>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val tok = line.split(WHITESPACE)
            when (tok[0]) {
                HEADER -> {
                    require(tok.size == 2 && tok[1] == VERSION) { "unsupported fault-schedule header: '$line'" }
                    sawHeader = true
                }
                "seed" -> {
                    require(tok.size == 2) { "seed expects exactly one value: '$line'" }
                    require(seed == null) { "duplicate seed directive: '$line'" }
                    seed = tok[1].toLongOrThrow("seed")
                }
                "reset" -> {
                    require(tok.size == 2) { "reset expects exactly one value: '$line'" }
                    require(termination is Termination.None) { "duplicate reset directive: '$line'" }
                    termination = Termination.ResetAfterUnits(tok[1].toIntOrThrow("reset count"))
                }
                "fault" -> faults += parseFaultLine(tok, line)
                else -> throw IllegalArgumentException("unknown fault-schedule directive '${tok[0]}': '$line'")
            }
        }

        require(sawHeader) { "missing '$HEADER $VERSION' header" }
        return FaultSchedule(faults.toList(), termination, seed ?: FaultSchedule.DEFAULT_SEED)
    }

    // ── encode helpers ──────────────────────────────────────────────────────

    private fun encodeSelector(selector: UnitSelector): String =
        when (selector) {
            is UnitSelector.All -> "all"
            is UnitSelector.Index -> "index ${selector.nth}"
            is UnitSelector.Every -> "every ${selector.n} ${selector.offset}"
            is UnitSelector.Range -> "range ${selector.fromInclusive} ${selector.untilExclusive}"
            is UnitSelector.From -> "from ${selector.fromInclusive}"
            is UnitSelector.Probabilistic -> "prob ${selector.probability}"
        }

    private fun encodeFault(fault: Fault): String =
        when (fault) {
            is Fault.Delay -> "delay ${fault.by.inWholeNanoseconds} ${fault.jitter.inWholeNanoseconds}"
            is Fault.Drop -> "drop"
            is Fault.Duplicate -> "duplicate ${fault.spacing.inWholeNanoseconds}"
            is Fault.Corrupt -> "corrupt ${fault.offset} ${fault.flipMask}"
            is Fault.Reorder -> "reorder ${fault.maxHoldSlots}"
        }

    // ── decode helpers ──────────────────────────────────────────────────────

    /** Cursor-walks a `fault <selector...> <fault...>` line: selector first (its arity), then the fault. */
    private fun parseFaultLine(
        tok: List<String>,
        line: String,
    ): ScheduledFault {
        val cursor = TokenCursor(tok, start = 1, line = line)
        val selector = parseSelector(cursor)
        val fault = parseFault(cursor)
        require(cursor.atEnd) { "trailing tokens after fault on: '$line'" }
        return ScheduledFault(selector, fault)
    }

    private fun parseSelector(c: TokenCursor): UnitSelector =
        when (val keyword = c.next("selector")) {
            "all" -> UnitSelector.All
            "index" -> UnitSelector.Index(c.nextInt("index.nth"))
            "every" -> UnitSelector.Every(c.nextInt("every.n"), c.nextInt("every.offset"))
            "range" -> UnitSelector.Range(c.nextInt("range.from"), c.nextInt("range.until"))
            "from" -> UnitSelector.From(c.nextInt("from.nth"))
            "prob" -> UnitSelector.Probabilistic(c.nextDouble("prob.probability"))
            else -> throw IllegalArgumentException("unknown selector '$keyword' on: '${c.line}'")
        }

    private fun parseFault(c: TokenCursor): Fault =
        when (val keyword = c.next("fault")) {
            "delay" -> Fault.Delay(c.nextLong("delay.by").nanoseconds, c.nextLong("delay.jitter").nanoseconds)
            "drop" -> Fault.Drop
            "duplicate" -> Fault.Duplicate(c.nextLong("duplicate.spacing").nanoseconds)
            "corrupt" -> Fault.Corrupt(c.nextInt("corrupt.offset"), c.nextInt("corrupt.flipMask"))
            "reorder" -> Fault.Reorder(c.nextInt("reorder.maxHoldSlots"))
            else -> throw IllegalArgumentException("unknown fault '$keyword' on: '${c.line}'")
        }

    private val WHITESPACE = Regex("\\s+")

    private fun String.toLongOrThrow(what: String): Long =
        toLongOrNull() ?: throw IllegalArgumentException("$what must be a Long, was '$this'")

    private fun String.toIntOrThrow(what: String): Int =
        toIntOrNull() ?: throw IllegalArgumentException("$what must be an Int, was '$this'")

    /** A forward-only reader over a fault line's tokens, with descriptive errors on exhaustion/parse. */
    private class TokenCursor(
        private val tokens: List<String>,
        start: Int,
        val line: String,
    ) {
        private var i = start

        val atEnd: Boolean get() = i >= tokens.size

        fun next(what: String): String {
            require(i < tokens.size) { "expected $what but line ended: '$line'" }
            return tokens[i++]
        }

        fun nextInt(what: String): Int = next(what).toIntOrNull() ?: throw IllegalArgumentException("$what must be an Int on: '$line'")

        fun nextLong(what: String): Long = next(what).toLongOrNull() ?: throw IllegalArgumentException("$what must be a Long on: '$line'")

        fun nextDouble(what: String): Double =
            next(what).toDoubleOrNull() ?: throw IllegalArgumentException("$what must be a Double on: '$line'")
    }
}
