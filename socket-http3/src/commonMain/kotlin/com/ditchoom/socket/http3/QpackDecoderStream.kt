package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicStreamId
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
     * Only ever advanced after a write actually reached the wire.
     */
    private var acknowledgedInsertCount = InsertCount.ZERO

    /** Put one decoder-stream instruction on the wire. Called under the lock, in accounting order. */
    protected abstract suspend fun write(instruction: QpackDecoderInstruction)

    /**
     * Acknowledge [streamId]'s field section, recording the insertions its [requiredInsertCount]
     * implicitly covers.
     */
    suspend fun acknowledgeSection(
        streamId: QuicStreamId,
        requiredInsertCount: InsertCount,
    ) = lock.withLock {
        write(QpackDecoderInstruction.SectionAck(streamId))
        if (requiredInsertCount > acknowledgedInsertCount) acknowledgedInsertCount = requiredInsertCount
    }

    /**
     * Tell the peer's encoder we have processed insertions up to [upTo], as a delta against what it
     * already knows. Writes nothing when an acknowledgment has already carried the count that far — an
     * Increment of zero is itself a decoder-stream error (§4.4.3).
     */
    suspend fun reportInsertsUpTo(upTo: InsertCount) =
        lock.withLock {
            val increment = upTo - acknowledgedInsertCount
            if (increment <= InsertCountDelta.ZERO) return@withLock
            write(QpackDecoderInstruction.InsertCountIncrement(increment))
            // Only after a successful write: a failed one never reached the peer, so its count did not move.
            acknowledgedInsertCount = upTo
        }

    /** Tell the peer's encoder that [streamId]'s outstanding section references are abandoned (§4.4.2). */
    suspend fun cancelStream(streamId: QuicStreamId) =
        lock.withLock {
            // Carries no count, but still takes the lock: it is a write on the same single-writer stream,
            // and interleaving its bytes with an acknowledgment's would corrupt both.
            write(QpackDecoderInstruction.StreamCancellation(streamId))
        }
}
