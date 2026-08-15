package com.ditchoom.socket.http3

import kotlin.jvm.JvmInline

/**
 * A position in a QPACK dynamic table's insertion sequence — an **absolute** count of how many
 * entries have ever been inserted (RFC 9204 §2.1.1).
 *
 * `insertCount`, `requiredInsertCount`, `knownReceivedCount` and `acknowledgedInsertCount` are all
 * this kind. They are compared and assigned to each other, and the only way to derive a wire
 * increment from them is to subtract two of them — see [minus].
 *
 * ## Why this is a type and not a `Long`
 *
 * The QPACK bug fixed in #353 was, at its core, *a delta passed where a value derived from two
 * absolutes was required*: the decoder emitted `InsertCountIncrement(1)` — a flat delta — when the
 * correct increment is `insertCount - acknowledgedInsertCount`. Every quantity involved was a bare
 * `Long`, so the type system had nothing to say about it, and neither did review.
 *
 * `QpackEncoder.processDecoderInstruction` still shows both kinds in adjacent branches — one *adds*
 * a delta, the next *jumps* to an absolute — and with bare `Long`s nothing stopped an absolute
 * being added or a delta being assigned. Now the confusion is unrepresentable rather than
 * merely tested-for.
 */
@JvmInline
value class InsertCount(
    val value: Long,
) : Comparable<InsertCount> {
    init {
        require(value >= 0) { "Insert count must be non-negative, got $value" }
    }

    /**
     * The increment that carries a peer from [other] to this count — the **only** route from
     * absolutes to a delta.
     *
     * Requires `this >= other`: an insert count never runs backwards, so a negative result means
     * the two counts were subtracted in the wrong order, which is worth failing on rather than
     * silently emitting a nonsense increment the peer must then reject.
     *
     * ⚠️ That makes this a **partial** operation, so a caller holding two counts that were read at
     * different moments must compare before it subtracts — never subtract and then test the sign.
     * `QpackDecoder.emitInsertCountIncrement` does exactly that: its `upTo` is captured under a
     * different lock than the one it later compares under, so the peer's traffic decides the order
     * the two counts arrive in, and a `require` on that path would be remotely triggerable.
     */
    operator fun minus(other: InsertCount): InsertCountDelta {
        require(value >= other.value) {
            "Insert counts run forward only; $this - $other would be negative"
        }
        return InsertCountDelta(value - other.value)
    }

    /**
     * The increment that carries a peer from [other] to this count, or `null` when [other] already
     * covers it — the **total** form of [minus], for the counts that are not read at the same instant.
     *
     * [minus] is partial: it refuses reversed operands, because for two counts a caller holds at once
     * a backwards subtraction is a local bug. But a caller comparing a count captured under one lock
     * against one maintained under another does not know the order in advance — the peer's traffic
     * decides it — and there `this < other` is a legitimate "already reported", not a bug. Subtracting
     * first and testing the sign afterwards would make that a `require` the peer can trigger; this
     * asks the question in the only order that cannot throw.
     */
    fun advanceFrom(other: InsertCount): InsertCountDelta? = if (this <= other) null else this - other

    /** Advance by a wire increment — the only route from a delta back to an absolute. */
    operator fun plus(delta: InsertCountDelta): InsertCount = InsertCount(value + delta.value)

    override fun compareTo(other: InsertCount): Int = value.compareTo(other.value)

    override fun toString(): String = "InsertCount($value)"

    companion object {
        /** Nothing inserted yet — the starting point for every count of this kind. */
        val ZERO = InsertCount(0)
    }
}

/**
 * A wire increment carried by an Insert Count Increment instruction (RFC 9204 §4.4.3) — a
 * **relative** quantity, never a position.
 *
 * Deliberately has no `plus(InsertCount)`: a delta added to an absolute yields an absolute, and
 * that direction is spelled [InsertCount.plus] so the result lands in the right type. There is no
 * conversion from a delta to an [InsertCount] at all, which is what makes the #353 bug — emitting a
 * bare `1` where a difference belonged — unwriteable.
 */
@JvmInline
value class InsertCountDelta(
    val value: Long,
) : Comparable<InsertCountDelta> {
    /**
     * Not `require(value > 0)`: a zero delta is a legal intermediate result of subtracting two
     * equal counts, and the "an increment must be positive on the wire" rule belongs at the
     * emit/validate boundary, where a peer's zero is a protocol violation with a typed error
     * rather than a local programming mistake. A *negative* one, though, cannot arise from
     * [InsertCount.minus] and cannot be decoded from the wire: `QpackPrefixedInteger.decode` bounds
     * the running sum *before* accumulating each continuation byte, so an over-long run raises a
     * `DecodeException` instead of wrapping into a negative. (Its non-consuming `peek` caps only the
     * shift, not the sum, and so can wrap — no count is ever built from a peek; peeks yield instruction
     * *lengths*, which `QpackInstructionReader` re-validates by decoding.) So a negative delta is a
     * bug wherever it appears.
     */
    init {
        require(value >= 0) { "Insert count delta must be non-negative, got $value" }
    }

    override fun compareTo(other: InsertCountDelta): Int = value.compareTo(other.value)

    override fun toString(): String = "InsertCountDelta($value)"

    companion object {
        /** No advance. Legal locally, a protocol violation on the wire (§4.4.3 increments are positive). */
        val ZERO = InsertCountDelta(0)
    }
}
