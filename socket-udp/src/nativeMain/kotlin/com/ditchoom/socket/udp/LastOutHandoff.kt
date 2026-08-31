package com.ditchoom.socket.udp

import kotlin.concurrent.AtomicInt

/**
 * Decides which party releases resources that in-flight users are still touching — the last one out —
 * and makes the decision in the same atomic step that records it.
 *
 * ## The shape it exists for (#498, #507, #526)
 *
 * Both native UDP backends had it. On Apple, `PosixUdpDatagramChannel.receive()` was a
 * check-then-dispatch: read a closed flag, then `withContext(recvDispatcher)`. `close()` was flag → fd
 * → `recvDispatcher.close()`. A receiver that had passed the flag and not yet dispatched met a dead
 * single-thread dispatcher, and kotlinx reported
 * `IllegalStateException: Dispatcher … was closed, attempted to schedule` where the caller was owed
 * `DatagramReadResult.Closed` (#498). The same receiver then called `recvfrom` on a descriptor number
 * `close()` had already closed, and once the process recycled that number it read *another socket's*
 * datagram (#507). No ordering of `close()`'s steps fixes either: whatever releases a resource has to
 * know nobody can still reach it, and a flag cannot say so — it records that closing began, not who is
 * still inside.
 *
 * Linux is the same ordering with a wider window (#526): `IoUringDatagramChannelCore.receive()` read the
 * flag and then called `IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_recvmsg(sqe, fd, …) }`,
 * whose lambda does **not** run at the call site — it is handed to a channel and invoked by the
 * process-global poller thread in its drain loop. So the descriptor number was read after a channel
 * hand-off *and* a poller iteration, and a submission could name a number `close()` had already closed
 * and the process had recycled. That is why this type lives in `nativeMain`: one answer, both backends.
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
