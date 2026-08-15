package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The QPACK decoder stream (RFC 9204 §4.4): the one thing that writes it, holding both its write lock
 * **and** the acknowledgment accounting that has to be ordered with those writes.
 *
 * ## Why an owner
 *
 * The decoder stream is a single QUIC stream, so its writes must be serialized — and they were, by a
 * `Mutex` the *connection* owned and passed into `Http3StreamWriter.writeDecoderInstruction`. Because
 * the lock lived with the caller, [QpackDecoder] could not see it, and #353's fix had to add a
 * *second* mutex of its own to make the accounting atomic with the write. Two locks guarding one
 * stream, and correctness depending on every caller remembering to pass the right one — nothing in
 * the type system paired a stream with its lock.
 *
 * Here there is one lock, it is not a parameter, and it cannot be mismatched: the object that holds
 * the count is the object that does the write.
 *
 * ## The rule this exists to keep
 *
 * A Section Acknowledgment does not only acknowledge its stream — it *implicitly* acknowledges every
 * insertion up to that section's Required Insert Count. So both instructions advance the peer's Known
 * Received Count, and an Insert Count Increment must cover only what is **not already covered that
 * way**: it is a delta against [acknowledgedInsertCount], never a flat one-per-insert.
 *
 * Emitting one per insert regardless double-counts, and the peer is required to kill the connection
 * for it (`QPACK_DECODER_STREAM_ERROR`, 0x202). Whether it fired depended purely on which instruction
 * won the race — increment-then-ack is harmless, because the ack's jump is then a no-op, but
 * ack-then-increment adds on top of a count the ack already moved. That is #353.
 *
 * The lock is held across the write on purpose: the count is only correct if the order instructions
 * reach the wire is the order they were accounted for. Computing under a lock and writing outside it
 * reintroduces the same race one level down.
 *
 * ## Subclassing, not a callback
 *
 * [write] is `protected abstract` rather than a constructor lambda so the accounting above lives in
 * exactly one place: a test double overrides [write] to record and therefore exercises the *real*
 * rule, instead of reimplementing it and agreeing with itself.
 */
abstract class QpackDecoderStream {
    private val lock = Mutex()

    /**
     * How many of our insertions we have told the peer's encoder about — by either instruction.
     * Only ever advanced after a write actually reached the wire, which is what [DecoderStreamWrite]
     * exists to make [write] say rather than imply.
     */
    private var acknowledgedInsertCount = InsertCount.ZERO

    /**
     * Put one decoder-stream instruction on the wire. Called under the lock, in accounting order.
     *
     * Returns what happened, because the accounting depends on it and a `Unit` return cannot tell
     * "wrote" from "silently did not". Both subclasses have a path that writes nothing and raises
     * nothing: the client swallows a [com.ditchoom.socket.quic.QuicCloseException] (the ack is moot
     * once the connection is gone) and the server returns early when its decoder stream is not open
     * yet. Under a `Unit` contract both of those advance [acknowledgedInsertCount] past instructions
     * the peer never received, and every later increment is short by that much — silently, since a
     * Known Received Count that lags is not a protocol error, just permanently degraded compression.
     * The connection-gone case is harmless; the not-open-yet case is on a live connection.
     */
    protected abstract suspend fun write(instruction: QpackDecoderInstruction): DecoderStreamWrite

    /**
     * Acknowledge [streamId]'s field section, recording the insertions its [requiredInsertCount]
     * implicitly covers.
     */
    suspend fun acknowledgeSection(
        streamId: QuicStreamId,
        requiredInsertCount: InsertCount,
    ) = withOwnedLock {
        if (write(QpackDecoderInstruction.SectionAck(streamId)) is DecoderStreamWrite.NotSent) return@withOwnedLock
        if (requiredInsertCount > acknowledgedInsertCount) acknowledgedInsertCount = requiredInsertCount
    }

    /**
     * Tell the peer's encoder we have processed insertions up to [upTo], as a delta against what it
     * already knows. Writes nothing when an acknowledgment has already carried the count that far — an
     * Increment of zero is itself a decoder-stream error (§4.4.3).
     */
    suspend fun reportInsertsUpTo(upTo: InsertCount) =
        withOwnedLock {
            // [InsertCount.advanceFrom], not `minus`: subtraction is partial, and [upTo] was captured
            // under a different lock (the decoder's table mutex), so by the time this coroutine holds
            // this one the acknowledged count may already have passed it — a Section Acknowledgment on
            // another coroutine is enough. Subtracting first and testing the sign second would let the
            // peer's traffic decide whether a `require` fires. Null means already covered.
            val increment = upTo.advanceFrom(acknowledgedInsertCount) ?: return@withOwnedLock
            if (write(QpackDecoderInstruction.InsertCountIncrement(increment)) is DecoderStreamWrite.NotSent) {
                return@withOwnedLock
            }
            // Only after a write that reached the wire: one that did not never reached the peer, so
            // its count did not move.
            acknowledgedInsertCount = upTo
        }

    /** Tell the peer's encoder that [streamId]'s outstanding section references are abandoned (§4.4.2). */
    suspend fun cancelStream(streamId: QuicStreamId) =
        withOwnedLock {
            // Carries no count, but still takes the lock: it is a write on the same single-writer stream,
            // and interleaving its bytes with an acknowledgment's would corrupt both.
            write(QpackDecoderInstruction.StreamCancellation(streamId))
            Unit
        }

    /**
     * Hold the lock, naming the current coroutine as its **owner**.
     *
     * The lock is held across [write], which a subclass supplies, so a `write` that re-entered this
     * object would otherwise deadlock on itself in silence. With an owner, `kotlinx.coroutines.Mutex`
     * fails that acquisition immediately with `IllegalStateException` instead. Nothing does it today —
     * both implementations put bytes on a QUIC stream — and this is what keeps it that way loudly. It
     * is the guard #353 added, kept rather than dropped when the accounting moved here: giving the
     * write a subclass to live in widened the surface it protects instead of removing the hazard.
     */
    private suspend inline fun <T> withOwnedLock(crossinline body: suspend () -> T): T =
        lock.withLock(currentCoroutineContext()[Job]) { body() }
}

/**
 * Whether a decoder-stream instruction reached the wire — the return of [QpackDecoderStream.write].
 *
 * Typed rather than a `Boolean` so the reason survives to whoever reads the code next: the two
 * not-sent cases have opposite consequences, and only one of them is benign.
 */
sealed interface DecoderStreamWrite {
    /** The bytes are on the stream. The accounting may advance. */
    data object Sent : DecoderStreamWrite

    /** Nothing was written; [why] says which case, and the accounting must not move. */
    data class NotSent(
        val why: NotSentReason,
    ) : DecoderStreamWrite

    /** Why a decoder-stream write produced no bytes. */
    sealed interface NotSentReason {
        /**
         * The connection is gone, so the acknowledgment is moot — there is no peer left to
         * desynchronize from. The benign case.
         */
        data class ConnectionClosed(
            val cause: Throwable,
        ) : NotSentReason

        /**
         * The decoder stream has not been opened yet. **Not** benign: the connection is live, and
         * anything that advanced the count here would leave the peer's Known Received Count
         * permanently short with no error anywhere.
         */
        data object StreamNotOpen : NotSentReason
    }
}
