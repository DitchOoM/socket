package com.ditchoom.socket.udp

import kotlin.concurrent.AtomicInt

/**
 * Decides which party closes a resource that in-flight users hop onto — the last one out — and
 * makes the decision in the same atomic step that records it.
 *
 * ## The shape it exists for (#498)
 *
 * `PosixUdpDatagramChannel.receive()` was a check-then-dispatch: read a closed flag, then
 * `withContext(recvDispatcher)`. `close()` was flag → fd → `recvDispatcher.close()`. A receiver that
 * had passed the flag and not yet dispatched met a dead single-thread dispatcher, and kotlinx reported
 * `IllegalStateException: Dispatcher … was closed, attempted to schedule` where the caller was owed
 * `DatagramReadResult.Closed`. No ordering of `close()`'s three steps fixes that: whatever closes the
 * dispatcher has to know nobody can still dispatch onto it, and a flag cannot say so — it records that
 * closing began, not who is still inside.
 *
 * ## One word, one CAS
 *
 * Bit 0 is "closed"; the remaining bits count the users currently admitted. Every transition is a
 * single compare-and-set on that word, so each question is answered by the step that changes the
 * answer:
 *
 *  - [enter] admits a user *and* counts it in the same CAS, or is refused because the bit is set.
 *    Refused means "you never touched the resource", so the caller returns its typed end without a
 *    hop.
 *  - [exit] uncounts a user; if that leaves the word at exactly closed-and-empty, this user was the
 *    last one out and owns the close.
 *  - [close] sets the bit; if the word was empty, nobody is inside and the closer owns the close,
 *    otherwise the close is handed off to whichever admitted user leaves last.
 *
 * Exactly one party ever sees [Departure.LastOut] or [Closing.LastOut]: the word reaches
 * closed-and-empty once (nothing increments after the bit is set, and a second [close] is
 * [Closing.AlreadyClosed]), and only the CAS that lands it there reports it. That is the once-only
 * latch — it is the word itself, not a second field with its own window.
 *
 * ## What this is deliberately not
 *
 * Not root `:socket`'s `TeardownOnce`. That type serialises *closers* racing each other over a
 * suspending teardown and makes the losers wait for the winner. This one serialises a closer against
 * *users*: it is non-suspending, nobody waits, and its only job is to name the last party out. The two
 * share a lesson — decide and record in one atomic step — and nothing else.
 */
internal class LastOutHandoff {
    private val state = AtomicInt(OPEN_EMPTY)

    /** Whether [close] has been called. The fence for fast-failing entry points (`send`, `isOpen`). */
    val closed: Boolean get() = state.value and CLOSED_BIT != 0

    /** What [enter] decided. */
    sealed interface Admission {
        /** Counted in; the caller must [exit] exactly once, on every path. */
        data object Admitted : Admission

        /** The resource is closed; the caller never touched it and owes no [exit]. */
        data object Refused : Admission
    }

    /** What [exit] decided. */
    sealed interface Departure {
        /** Someone else is still inside, or the resource is not closed: not this caller's close. */
        data object NotLast : Departure

        /** The resource is closed and this caller was the last user out: it closes the resource. */
        data object LastOut : Departure
    }

    /** What [close] decided. */
    sealed interface Closing {
        /** A previous [close] already decided; nothing to do. */
        data object AlreadyClosed : Closing

        /** Users are inside; the last of them to [exit] closes the resource. */
        data object HandedOff : Closing

        /** Nobody is inside and nobody can be admitted now: the closer closes the resource. */
        data object LastOut : Closing
    }

    fun enter(): Admission {
        while (true) {
            val current = state.value
            if (current and CLOSED_BIT != 0) return Admission.Refused
            if (state.compareAndSet(current, current + ONE_USER)) return Admission.Admitted
        }
    }

    fun exit(): Departure {
        while (true) {
            val current = state.value
            check(current >= ONE_USER) { "exit() without a matching admitted enter()" }
            val next = current - ONE_USER
            if (state.compareAndSet(current, next)) {
                return if (next == CLOSED_EMPTY) Departure.LastOut else Departure.NotLast
            }
        }
    }

    fun close(): Closing {
        while (true) {
            val current = state.value
            if (current and CLOSED_BIT != 0) return Closing.AlreadyClosed
            val next = current or CLOSED_BIT
            if (state.compareAndSet(current, next)) {
                return if (next == CLOSED_EMPTY) Closing.LastOut else Closing.HandedOff
            }
        }
    }

    private companion object {
        const val CLOSED_BIT = 1
        const val ONE_USER = 2
        const val OPEN_EMPTY = 0
        const val CLOSED_EMPTY = CLOSED_BIT
    }
}
