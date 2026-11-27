@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: the rebind ladder's states and steps.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span

/** Where a silent path's rebinds stand: the ladder of asks for a fresh port, kept across the path's silences. */
internal sealed interface Ladder {
    /** No rebind was ever asked, or network news started the ladder again: the next one may be asked at once. */
    data object Fresh : Ladder

    /** The path answers; the next silence's rebind may be asked from [due], [gap] after it, decayed by the time since. */
    class Rested(
        val due: Nanos,
        val gap: Span,
    ) : Ladder

    /** The path is silent: a fresh port where [wanted] says is owed at [due], and the rung after it [gap] later. */
    class Owed(
        val wanted: MoveWanted,
        val due: Nanos,
        val gap: Span,
    ) : Ladder
}

/** The ladder once the path answers: an owed rung is no longer owed, and its due time and gap are kept. */
internal fun rest(ladder: Ladder): Ladder =
    when (ladder) {
        is Ladder.Fresh, is Ladder.Rested -> ladder
        is Ladder.Owed -> Ladder.Rested(ladder.due, ladder.gap)
    }

/** The ladder once a rebind proved needless: rested, and the gap before the next doubled once more. */
internal fun wasted(ladder: Ladder): Ladder =
    when (ladder) {
        is Ladder.Fresh, is Ladder.Rested -> ladder
        is Ladder.Owed -> Ladder.Rested(ladder.due, Span(minOf(ladder.gap.nanos * 2, Rebinds.LONGEST_WAIT.nanos)))
    }

internal fun later(
    a: Nanos,
    b: Nanos,
): Nanos = if (a < b) b else a

/**
 * A rested ladder's [gap], [past] its due time: halved for each [Rebinds.LONGEST_WAIT] gone by, down to the first wait,
 * so a path that answers for minutes is asked at once again, while one silent every few seconds spends ever fewer ids.
 */
internal fun decayed(
    gap: Span,
    past: Span,
): Span {
    val halvings = if (past.nanos <= 0L) 0L else past.nanos / Rebinds.LONGEST_WAIT.nanos
    val shifted = if (halvings >= MAX_HALVINGS) 0L else gap.nanos shr halvings.toInt()
    return Span(maxOf(shifted, Rebinds.FIRST_WAIT.nanos))
}

/** Past this many halvings a gap is at the first wait whatever it was. */
internal const val MAX_HALVINGS = 62L

/** Where the news a rebind ladder is renewed by points it. */
internal sealed interface Rebind {
    /** The address it asked for before. */
    data object Same : Rebind

    /** Where [wanted] says. */
    class To(
        val wanted: MoveWanted,
    ) : Rebind
}
