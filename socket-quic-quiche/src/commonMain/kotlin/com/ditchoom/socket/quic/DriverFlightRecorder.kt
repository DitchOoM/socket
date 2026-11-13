@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * The last steps one connection's driver loop and its stream readers took, kept so that a stalled
 * connection can be read **without the loop's help**.
 *
 * [QuicheDriver.stallSnapshot] is a command: it can only answer if the loop takes it. A loop that has
 * stopped taking commands, which is the stall that most needs explaining, answers nothing. This
 * recorder is written on the loop and in the readers, and read from any thread. Its last entries say
 * where the loop last was:
 * - parked in its wait while commands queue up behind it, meaning its wakeup was dispatched and never
 *   ran;
 * - inside a datagram send that has not returned;
 * - or still idle with nothing queued, meaning the stall is waiting on someone else.
 *
 * Per stream, it also shows the reader parking on its data wake, the loop signalling that stream
 * readable, and the reader resuming. A signalled stream whose parked reader never resumed shows up as
 * exactly that.
 *
 * **Off by default.** A driver only builds one when trace capture is on ([QuicOptions.trace]), which is
 * already the opt-in for diagnosing a connection. When it is on, recording costs one atomic increment
 * and four array stores per event: the ring is preallocated, and nothing is formatted until [render].
 * A slot being written while [render] reads it can come out torn. That is accepted for a diagnostic,
 * and the recorder never makes a reader wait for it.
 */
internal sealed interface DriverFlight {
    data object Off : DriverFlight

    class Ring(
        capacity: Int = DEFAULT_CAPACITY,
    ) : DriverFlight {
        private val size = capacity
        private val origin = TimeSource.Monotonic.markNow()
        private val at = LongArray(size)
        private val kinds = arrayOfNulls<FlightEvent>(size)
        private val a = LongArray(size)
        private val refs = arrayOfNulls<Any>(size)
        private val written = AtomicLong(0L)

        fun record(
            event: FlightEvent,
            arg: Long,
            ref: Any?,
        ) {
            val slot = (written.fetchAndIncrement() % size).toInt()
            at[slot] = origin.elapsedNow().inWholeNanoseconds
            a[slot] = arg
            refs[slot] = ref
            kinds[slot] = event
        }

        /** The recorded entries, oldest first, each timed back from now. Allocates; diagnostics only. */
        fun entries(): List<FlightEntry> {
            val now = origin.elapsedNow().inWholeNanoseconds
            val end = written.load()
            val start = maxOf(0L, end - size)
            return (start until end).mapNotNull { index ->
                val slot = (index % size).toInt()
                val kind = kinds[slot] ?: return@mapNotNull null
                FlightEntry(index, kind, (now - at[slot]).nanoseconds, a[slot], refs[slot])
            }
        }

        companion object {
            const val DEFAULT_CAPACITY = 1024
        }
    }
}

/** One recorded step: [index] counts every event this driver recorded, so a gap shows overwritten ones. */
internal data class FlightEntry(
    val index: Long,
    val event: FlightEvent,
    val ago: kotlin.time.Duration,
    val arg: Long,
    val ref: Any?,
) {
    override fun toString(): String = "#$index ${ago.inWholeMicroseconds}us ago ${event.describe(arg, ref)}"
}

/**
 * What a [FlightEntry] records. Each kind says what its argument and its reference hold, and [describe]
 * renders them, so a recording site stores numbers and never builds a string.
 */
internal enum class FlightEvent {
    /** The loop started its wait. arg: the armed wait in milliseconds, or -1 for none (commands only). */
    LoopParked,

    /** The loop's wait ended with a command. ref: the command's class. */
    WokeByCommand,

    /** The loop's wait ended on its timer. arg: the [WakeKind] ordinal. */
    WokeByTimer,

    /** A datagram send started. arg: its length. */
    SendBegin,

    /** That send returned. arg: 0 sent, 1 failed, 2 stalled past the bound. */
    SendEnd,

    /** The loop told a stream's reader that quiche holds data or an end for it. arg: the stream id. */
    StreamSignalled,

    /** A reader found nothing and parked on its stream's data wake. arg: the stream id. */
    ReaderParked,

    /** That reader's park returned. arg: the stream id. */
    ReaderResumed,

    /** A read handed bytes to its caller. arg: the stream id. */
    ReadReturned,

    /** A turn held its flush for the application's reply ([AckHold]). */
    FlushHeld,
    ;

    fun describe(
        arg: Long,
        ref: Any?,
    ): String =
        when (this) {
            LoopParked -> if (arg < 0) "LoopParked untimed" else "LoopParked wait=${arg}ms"
            WokeByCommand -> "WokeByCommand ${(ref as? kotlin.reflect.KClass<*>)?.simpleName ?: ref}"
            WokeByTimer -> "WokeByTimer ${WakeKind.entries.getOrNull(arg.toInt()) ?: arg}"
            SendBegin -> "SendBegin len=$arg"
            SendEnd -> "SendEnd ${listOf("sent", "failed", "stalled").getOrNull(arg.toInt()) ?: arg}"
            StreamSignalled, ReaderParked, ReaderResumed, ReadReturned -> "$name stream=$arg"
            FlushHeld -> "FlushHeld"
        }
}

/** Record [event], or nothing at all when the recorder is off. Inline, so off costs one branch. */
internal inline fun DriverFlight.record(
    event: FlightEvent,
    arg: Long = 0L,
    ref: Any? = null,
) {
    when (this) {
        DriverFlight.Off -> Unit
        is DriverFlight.Ring -> record(event, arg, ref)
    }
}

/**
 * What the loop was doing when the recording ends, read from its last loop event and from whether
 * commands were waiting for it at that moment. [commandsQueued] comes from the caller, which reads the
 * command channel at the same time as the entries.
 */
internal sealed interface LoopVerdict {
    /** Nothing recorded, or the recorder is off. */
    data object Unknown : LoopVerdict

    /** Parked in its wait with nothing queued: the loop is idle and the stall is elsewhere. */
    data class IdleNothingQueued(
        val parkedFor: kotlin.time.Duration,
    ) : LoopVerdict

    /**
     * Parked in its wait while commands are queued. A command sent to a parked loop dispatches its
     * wakeup, so the loop's coroutine was resumed and has not run since: it is starved, not waiting.
     */
    data class ResumedNeverRan(
        val parkedFor: kotlin.time.Duration,
    ) : LoopVerdict

    /** Inside a datagram send that has not returned. */
    data class InsideSend(
        val sendingFor: kotlin.time.Duration,
    ) : LoopVerdict

    /** Neither parked nor sending: between steps of one turn, after [last]. */
    data class MidTurn(
        val last: FlightEntry,
    ) : LoopVerdict
}

private val loopEvents =
    setOf(FlightEvent.LoopParked, FlightEvent.WokeByCommand, FlightEvent.WokeByTimer, FlightEvent.SendBegin, FlightEvent.SendEnd)

internal fun loopVerdictOf(
    entries: List<FlightEntry>,
    commandsQueued: Boolean,
): LoopVerdict {
    val last = entries.lastOrNull { it.event in loopEvents } ?: return LoopVerdict.Unknown
    return when (last.event) {
        FlightEvent.LoopParked ->
            if (commandsQueued) LoopVerdict.ResumedNeverRan(last.ago) else LoopVerdict.IdleNothingQueued(last.ago)
        FlightEvent.SendBegin -> LoopVerdict.InsideSend(last.ago)
        else -> LoopVerdict.MidTurn(last)
    }
}

/**
 * Per stream, the reader parks that no resume followed, the loop having signalled that stream after
 * the park: the lost-wakeup shape. Empty when every parked reader resumed or was never signalled.
 */
internal fun unresumedSignalledReaders(entries: List<FlightEntry>): List<Long> {
    val parkedSince = HashMap<Long, Long>()
    val signalledAfterPark = HashSet<Long>()
    for (entry in entries) {
        when (entry.event) {
            FlightEvent.ReaderParked -> {
                parkedSince[entry.arg] = entry.index
                signalledAfterPark.remove(entry.arg)
            }
            FlightEvent.StreamSignalled -> if (entry.arg in parkedSince) signalledAfterPark.add(entry.arg)
            FlightEvent.ReaderResumed -> {
                parkedSince.remove(entry.arg)
                signalledAfterPark.remove(entry.arg)
            }
            else -> Unit
        }
    }
    return signalledAfterPark.sorted()
}

/** [QuicheDriver.flightRecording]: what the recorder holds, read off the loop. */
internal sealed interface FlightRecording {
    /** Trace capture is off, so nothing was recorded. */
    data object Off : FlightRecording

    class Recorded(
        val role: QuicRole,
        val verdict: LoopVerdict,
        /** Streams the loop signalled after their reader parked, with no resume since. */
        val signalledReadersNotResumed: List<Long>,
        val entries: List<FlightEntry>,
    ) : FlightRecording {
        /** The verdict, the unresumed readers, then the last [tail] entries, one per line. */
        fun render(tail: Int = entries.size): String =
            buildString {
                append("flight recorder (").append(role).append("): loop ").append(verdict)
                append("; signalled readers not resumed=").append(signalledReadersNotResumed)
                val shown = entries.takeLast(tail)
                append("; last ")
                    .append(shown.size)
                    .append(" of ")
                    .append(entries.size)
                    .append(" entries:")
                shown.forEach { append("\n  ").append(it) }
            }
    }
}
