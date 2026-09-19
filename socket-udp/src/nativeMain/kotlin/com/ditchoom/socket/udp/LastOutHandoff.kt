package com.ditchoom.socket.udp

import kotlin.concurrent.AtomicInt

/**
 * Decides which party releases resources that in-flight users are still touching — the last one out —
 * and makes the decision in the same atomic step that records it.
 *
 * ## The shape it exists for
 *
 * Both native UDP backends receive by check-then-dispatch: a closed flag is read, and the syscall runs
 * somewhere else — on Apple after a `withContext(recvDispatcher)` hop, on Linux inside a lambda that
 * `IoUringManager.submitAndWait` hands to the process-global poller thread, which invokes it in its
 * drain loop after a channel hand-off *and* a poller iteration. A `close()` that closed the dispatcher
 * and the descriptor behind such a flag would let a receiver that has passed the flag meet a dead
 * single-thread dispatcher (`IllegalStateException: Dispatcher … was closed, attempted to schedule`
 * where the caller is owed `DatagramReadResult.Closed`), or call `recvfrom`/`recvmsg` on a descriptor
 * number the process has already recycled and read *another socket's* datagram. No ordering of
 * `close()`'s steps fixes either: whatever releases a resource has to know nobody can still reach it,
 * and a flag cannot say so — it records that closing began, not who is still inside. That is why this
 * type lives in `nativeMain`: one answer, both backends.
 *
 * ## One word, one CAS
 *
 * Bit 0 is "closed"; the remaining bits count the parties currently admitted. Every transition is a
 * single compare-and-set on that word, so each question is answered by the step that changes the
 * answer:
 *
 *  - [enter] admits a user *and* counts it in the same CAS, or is refused because the bit is set.
 *    Refused means "you never touched the resource", so the caller returns its typed end without
 *    touching anything.
 *  - [close] sets the bit **and counts the closer in exactly like a user**, because a closer is one:
 *    it still has to reach the resource to wake the users inside (`PosixUdpDatagramChannel` writes to
 *    its wake pipe; `IoUringDatagramChannelCore` enqueues the cancel that retires the in-flight
 *    submission) before anyone may release it. It owes exactly one [exit], like any user.
 *  - [exit] uncounts a party; if that leaves the word at closed-and-empty, this party was the last one
 *    out and owns the release.
 *
 * Exactly one party ever sees [Departure.LastOut]: the word reaches closed-and-empty once (nothing
 * increments after the bit is set, and a second [close] is [Closing.AlreadyClosed]), and only the CAS
 * that lands it there reports it. That is the once-only latch — it is the word itself, not a second
 * field with its own window. And because the closer is counted, there is no state in which a release
 * is owed while the closer has not finished waking: the last-out party is genuinely last.
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

    /** Whether [close] has been called. The fence for reporting a closed channel (`isOpen`). */
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
        /** Someone else is still inside, or the resource is not closed: not this caller's release. */
        data object NotLast : Departure

        /** The resource is closed and this caller was the last party out: it releases the resource. */
        data object LastOut : Departure
    }

    /** What [close] decided. */
    sealed interface Closing {
        /** A previous [close] already decided; nothing to do, and this caller owes no [exit]. */
        data object AlreadyClosed : Closing

        /**
         * This call set the closed bit and is counted in like any admitted user: nobody can be admitted
         * from here on, no other party can release the resource while this one is inside, and this
         * caller owes exactly one [exit] — which is what tells it whether it was last out.
         */
        data object Admitted : Closing
    }

    fun enter(): Admission {
        while (true) {
            val current = state.value
            if (current and CLOSED_BIT != 0) return Admission.Refused
            if (state.compareAndSet(current, current + ONE_PARTY)) return Admission.Admitted
        }
    }

    fun exit(): Departure {
        while (true) {
            val current = state.value
            check(current >= ONE_PARTY) { "exit() without a matching admitted enter()/close()" }
            val next = current - ONE_PARTY
            if (state.compareAndSet(current, next)) {
                return if (next == CLOSED_EMPTY) Departure.LastOut else Departure.NotLast
            }
        }
    }

    fun close(): Closing {
        while (true) {
            val current = state.value
            if (current and CLOSED_BIT != 0) return Closing.AlreadyClosed
            if (state.compareAndSet(current, (current or CLOSED_BIT) + ONE_PARTY)) return Closing.Admitted
        }
    }

    private companion object {
        const val CLOSED_BIT = 1
        const val ONE_PARTY = 2
        const val OPEN_EMPTY = 0
        const val CLOSED_EMPTY = CLOSED_BIT
    }
}
