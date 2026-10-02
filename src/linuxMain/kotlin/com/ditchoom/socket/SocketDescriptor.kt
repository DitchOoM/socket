package com.ditchoom.socket

import com.ditchoom.socket.linux.io_uring_prep_nop
import com.ditchoom.socket.linux.io_uring_sqe
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.time.Duration

/**
 * A socket's descriptor, owned so that no operation can ever name its number after the number is freed.
 *
 * An io_uring operation names the descriptor by number, and the poller thread builds the SQE from that number
 * only when it drains the operation — after a channel hand-off and a poller iteration. A close that ran
 * `close(2)` at once would free the number while such an operation waited, and the kernel would resolve it to
 * whatever the process opened next: a read would consume another connection's bytes, a write would send down
 * another connection, an accept would take another listener's client. So the descriptor is not closed by
 * [close] but by the last party out, the decision `socket-udp`'s `LastOutHandoff` makes for its channels:
 *
 *  - every use of the descriptor runs inside [withOpen] or [withOpenNow], which admits the caller and counts
 *    it in one compare-and-set on [state], or refuses it with a [SocketClosedException] once closing began;
 *  - [close] sets the closed bit, counts itself in like a user, wakes whatever is parked, and leaves;
 *  - whoever leaves a closed descriptor empty runs [teardown], closes the descriptor and uncounts the socket.
 *
 * Nothing increments after the bit is set, so the word reaches closed-and-empty exactly once, and exactly one
 * party releases.
 *
 * [adopt] counts the socket in [IoUringManager]'s count of open sockets in the same step its descriptor
 * becomes the socket's, so the count moves by exactly one each way.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SocketDescriptor(
    /** What must happen while the descriptor is still open and nobody else is using it, such as a TLS close_notify. */
    private val teardown: () -> Unit = {},
) {
    private val descriptor = AtomicInt(NO_DESCRIPTOR)

    /** Bit 0: closing began. The remaining bits: parties admitted and not yet left. */
    private val state = AtomicInt(OPEN_EMPTY)

    /** The `user_data` of each lane's operation once the poller has prepared it, or [NO_OPERATION]. */
    private val inFlight = Array(Lane.entries.size) { AtomicLong(NO_OPERATION) }

    val isOpen: Boolean get() = state.value and CLOSED_BIT == 0 && descriptor.value >= 0

    /** Who submits on a descriptor concurrently: one reader and one writer. Each lane has one operation in flight. */
    enum class Lane { Read, Write }

    /**
     * Makes [fd] this socket's descriptor and counts the socket. A socket closed before its descriptor arrived
     * (a close during the connect) closes [fd] at once instead.
     */
    fun adopt(fd: Int) {
        check(fd >= 0) { "not a descriptor: $fd" }
        if (!enter()) {
            closeSocket(fd)
            throw SocketClosedException.General("Socket closed before its connection completed")
        }
        check(descriptor.compareAndSet(NO_DESCRIPTOR, fd)) { "the socket already holds descriptor ${descriptor.value}" }
        IoUringManager.onSocketOpened()
        if (exit()) release()
    }

    /** Runs [block] with the descriptor while it cannot be closed under it. */
    suspend fun <T> withOpen(block: suspend (fd: Int) -> T): T {
        val fd = admit()
        try {
            return block(fd)
        } finally {
            // The release ends in [IoUringManager.onSocketClosed], which may block on the poller; a caller the
            // poller resumed would wait on itself. NonCancellable: cancellation is the usual reason control is
            // here, and a skipped release would leak the descriptor.
            if (exit()) withContext(NonCancellable + Dispatchers.Default) { release() }
        }
    }

    /** [withOpen] for a call that does not suspend, so it cannot be resumed on the poller. */
    fun <T> withOpenNow(block: (fd: Int) -> T): T {
        val fd = admit()
        try {
            return block(fd)
        } finally {
            if (exit()) release()
        }
    }

    /** [withOpenNow], or [whenClosed] without running [block] once closing began or before a descriptor arrived. */
    fun <T> withOpenNowOr(
        whenClosed: T,
        block: (fd: Int) -> T,
    ): T {
        if (!enter()) return whenClosed
        try {
            val fd = descriptor.value
            return if (fd < 0) whenClosed else block(fd)
        } finally {
            if (exit()) release()
        }
    }

    /**
     * Submits one operation on [fd] — the descriptor this caller holds through [withOpen] — and suspends
     * until it completes. The poller prepares it only if closing has not begun; otherwise it prepares a no-op
     * and this throws [SocketClosedException], so the operation never names the descriptor after [close].
     */
    suspend fun submit(
        fd: Int,
        lane: Lane,
        timeout: Duration?,
        prepare: (sqe: CPointer<io_uring_sqe>, fd: Int) -> Unit,
    ): Int {
        val slot = inFlight[lane.ordinal]
        val prepared = AtomicLong(NO_OPERATION)
        val refused = AtomicInt(0)
        val result =
            try {
                IoUringManager.submitAndWait(timeout) { sqe, userData ->
                    // Publish, then check: [close] sets the bit, then reads the slot. Each side writes before it
                    // reads, so at least one sees the other — this prepares a no-op, or [close] cancels this
                    // user_data, which it enqueues behind the SQE being prepared now.
                    prepared.value = userData
                    slot.value = userData
                    if (state.value and CLOSED_BIT != 0) {
                        refused.value = 1
                        io_uring_prep_nop(sqe)
                    } else {
                        prepare(sqe, fd)
                    }
                }
            } finally {
                slot.compareAndSet(prepared.value, NO_OPERATION)
            }
        // A no-op completes with 0, which a read would take for end-of-stream and an accept for descriptor 0.
        if (refused.value != 0) throw SocketClosedException.General("Socket is closed")
        return result
    }

    /** The `user_data` of [lane]'s operation while the poller has it prepared, else 0. */
    fun inFlight(lane: Lane): Long = inFlight[lane.ordinal].value

    /**
     * Refuses every further use, cancels each lane's prepared operation so it completes at once, and leaves
     * like any user — releasing the descriptor only if nobody else is still using it.
     */
    fun close() {
        if (!beginClosing()) return
        // After the bit is set: see [submit]. A lane with nothing prepared has nothing to cancel.
        // MUTATION: no cancel on close
        if (exit()) release()
    }

    private fun admit(): Int {
        if (!enter()) throw SocketClosedException.General("Socket is closed")
        val fd = descriptor.value
        if (fd < 0) {
            if (exit()) release()
            throw SocketClosedException.General("Socket is not connected")
        }
        return fd
    }

    private fun enter(): Boolean {
        while (true) {
            val current = state.value
            if (current and CLOSED_BIT != 0) return false
            if (state.compareAndSet(current, current + ONE_PARTY)) return true
        }
    }

    /** Sets the closed bit and counts the closer in; false when closing had already begun. */
    private fun beginClosing(): Boolean {
        while (true) {
            val current = state.value
            if (current and CLOSED_BIT != 0) return false
            if (state.compareAndSet(current, (current or CLOSED_BIT) + ONE_PARTY)) return true
        }
    }

    /** Uncounts this party; true when it left a closed descriptor empty, which makes it the releaser. */
    private fun exit(): Boolean {
        while (true) {
            val current = state.value
            check(current >= ONE_PARTY) { "left a socket descriptor without being admitted" }
            val next = current - ONE_PARTY
            if (state.compareAndSet(current, next)) return next == CLOSED_EMPTY
        }
    }

    /** Reached by exactly one party. A descriptor that never arrived has nothing to tear down or uncount. */
    private fun release() {
        val fd = descriptor.value
        if (fd < 0) return
        try {
            teardown()
        } finally {
            closeSocket(fd)
            IoUringManager.onSocketClosed()
        }
    }

    private companion object {
        const val NO_DESCRIPTOR = -1
        const val NO_OPERATION = 0L
        const val CLOSED_BIT = 1
        const val ONE_PARTY = 2
        const val OPEN_EMPTY = 0
        const val CLOSED_EMPTY = CLOSED_BIT
    }
}
