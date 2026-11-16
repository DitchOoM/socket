@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What an [OwnerWord] says, decoded. Diagnostics and tests; the hot path never decodes. */
internal sealed interface Owner {
    data object Free : Owner

    class Held(
        val entrant: Entrant,
    ) : Owner

    /** Held, and work was left in the connection's inbox: the holder drains it before it may leave. */
    class HeldWithWork(
        val entrant: Entrant,
    ) : Owner
}

internal enum class Entry { Entered, Busy }

internal enum class WorkLeft {
    /** The connection is held; its holder runs the work before it leaves. */
    ForHolder,

    /** Nobody holds the connection: whoever left the work must enter and run it, or hand it to the loop. */
    NobodyInside,
}

internal enum class Leave {
    Left,

    /** Work arrived while inside. The word is back to plain held: drain the inbox, then leave again. */
    WorkArrived,
}

/**
 * The one atomic word that says who is inside a connection.
 *
 * ```
 * bits 63..62  00 free | 01 held | 10 held with work
 * bits 61..0   the entrant
 * ```
 *
 * Every transition is one compare-and-set. Work for a connection goes to its inbox first and then [leaveWork]
 * is called, so a holder cannot leave while work is pending: [tryLeave] fails and the holder drains. Work is
 * never stranded, and two entrants inside at once has no encoding.
 */
@Published
internal class OwnerWord {
    private val word = AtomicLong(FREE)

    fun tryEnter(me: Entrant): Entry = if (word.compareAndSet(FREE, HELD or me.code)) Entry.Entered else Entry.Busy

    /** Called after pushing work to the connection's inbox. */
    fun leaveWork(): WorkLeft {
        while (true) {
            val seen = word.load()
            when (seen and STATE_MASK) {
                FREE -> return WorkLeft.NobodyInside
                HELD_WITH_WORK -> return WorkLeft.ForHolder
                else -> if (word.compareAndSet(seen, HELD_WITH_WORK or (seen and ENTRANT_MASK))) return WorkLeft.ForHolder
            }
        }
    }

    /** Only the holder calls this. */
    fun tryLeave(me: Entrant): Leave {
        if (word.compareAndSet(HELD or me.code, FREE)) return Leave.Left
        // The only other state the holder can find is its own word with work pending.
        word.store(HELD or me.code)
        return Leave.WorkArrived
    }

    fun owner(): Owner {
        val seen = word.load()
        val entrant = Entrant.decode(seen)
        return when (seen and STATE_MASK) {
            FREE -> Owner.Free
            HELD -> Owner.Held(entrant)
            else -> Owner.HeldWithWork(entrant)
        }
    }
}

private const val STATE_MASK = 3L shl 62
private const val FREE = 0L
private const val HELD = 1L shl 62
private const val HELD_WITH_WORK = 2L shl 62
private const val ENTRANT_MASK = (1L shl 62) - 1
