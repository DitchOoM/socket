package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

/**
 * What a loop turn did, as far as the ACK hold cares. A turn starts as [TimerOnly]; each command it runs
 * moves it with [after], and the strongest thing it did is what it stays.
 */
internal enum class TurnWork {
    /** No command ran: a timer woke the loop. */
    TimerOnly,

    /** Only datagrams from the peer were fed to quiche. */
    DatagramsOnly,

    /** Something other than a datagram or an application write ran: a read, an open, a close, a migration step. */
    Other,

    /** The application wrote: stream bytes or a datagram for the peer. */
    ApplicationWrite,
    ;

    /** This turn's work once it has also done [next]. */
    fun after(next: TurnWork): TurnWork = if (next.ordinal > ordinal) next else this
}

/** Whether a flush the loop is about to make is held back. */
internal sealed interface AckHold {
    /** Nothing is held: the turn flushes as usual. */
    data object NotHolding : AckHold

    /** The flush has been held since [since] and leaves no later than [AckHoldGate.BOUND] after it. */
    class Holding(
        val since: TimeMark,
    ) : AckHold {
        /** How long until the held flush must leave. */
        fun remaining(): Duration = (AckHoldGate.BOUND - since.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }
}

/** What [AckHoldGate.offer] answers for a turn that could hold its flush. */
internal enum class HoldDecision { Hold, FlushNow }

/**
 * Decides whether a turn that only received one datagram, and handed its stream bytes to a read that was
 * waiting for them, holds its flush for the application's reply.
 *
 * quiche writes an ACK in whatever flush follows a received packet. A request/response peer answers
 * within tens of microseconds, so a flush made the moment the request arrives sends an ACK alone, and
 * the reply follows in a datagram of its own: two datagrams, two encryptions and two wake-ups of the
 * peer where one carries both. Holding the flush until the reply is written puts them in one.
 *
 * The hold is bounded by [BOUND], well inside the `max_ack_delay` every QUIC peer allows for (RFC 9000
 * §13.2.1), and it is only offered where the reply is likely: the gate counts holds that ended without
 * a reply. After [MISSES_BEFORE_SHUT] in a row it stops holding for [FIRST_SHUT_TURNS] eligible turns,
 * then holds once on probation: a reply opens it again, another miss shuts it for twice as long, up to
 * [LONGEST_SHUT_TURNS]. A bulk receive or a peer that does not answer therefore pays for two held
 * flushes and then one in thousands.
 *
 * Confined to the driver loop, like everything the loop reads: no lock, no atomics.
 */
internal class AckHoldGate {
    private sealed interface Gate {
        class Open(
            val missesInARow: Int,
        ) : Gate

        /** Not holding for [turnsLeft] more eligible turns; then on [Probation] with [shutFor] as the last shut. */
        class Shut(
            val turnsLeft: Int,
            val shutFor: Int,
        ) : Gate

        /** One hold decides: a reply opens the gate, a miss shuts it for twice [lastShutFor]. */
        class Probation(
            val lastShutFor: Int,
        ) : Gate
    }

    private var gate: Gate = Gate.Open(missesInARow = 0)

    /** A turn could hold its flush: may it? */
    fun offer(): HoldDecision =
        when (val g = gate) {
            is Gate.Open, is Gate.Probation -> HoldDecision.Hold
            is Gate.Shut -> {
                gate = if (g.turnsLeft <= 1) Gate.Probation(g.shutFor) else Gate.Shut(g.turnsLeft - 1, g.shutFor)
                HoldDecision.FlushNow
            }
        }

    /** A held flush left with the application's reply in it. */
    fun replied() {
        gate = Gate.Open(missesInARow = 0)
    }

    /** A held flush left without a reply: the bound ran out, or something else needed the flush first. */
    fun missed() {
        gate =
            when (val g = gate) {
                is Gate.Open ->
                    if (g.missesInARow + 1 >=
                        MISSES_BEFORE_SHUT
                    ) {
                        Gate.Shut(FIRST_SHUT_TURNS, FIRST_SHUT_TURNS)
                    } else {
                        Gate.Open(g.missesInARow + 1)
                    }
                is Gate.Probation -> (g.lastShutFor * 2).coerceAtMost(LONGEST_SHUT_TURNS).let { Gate.Shut(it, it) }
                // A miss is reported for a hold, and a shut gate grants none; nothing to count.
                is Gate.Shut -> g
            }
    }

    companion object {
        /** The longest a flush is held. One tick of the loop's timer, which counts in milliseconds. */
        val BOUND: Duration = 1.milliseconds

        const val MISSES_BEFORE_SHUT = 2
        const val FIRST_SHUT_TURNS = 256
        const val LONGEST_SHUT_TURNS = 4096
    }
}
