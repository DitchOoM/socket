package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stateful QPACK **decoder** for one connection (RFC 9204): owns the decoder dynamic table, applies
 * the peer's encoder-stream instructions to it ([applyEncoderInstruction]), and decodes encoded field
 * sections ([decodeSection]) — static and dynamic representations alike. Field sections that reference
 * not-yet-inserted entries **block** until [applyEncoderInstruction] catches the table up to their
 * Required Insert Count (RFC 9204 §2.2.1). Acknowledgments flow back to the peer's encoder via [emit].
 *
 * One instance per connection; [maxCapacity] is the `SETTINGS_QPACK_MAX_TABLE_CAPACITY` *we* advertise
 * (the ceiling the peer's encoder must respect). [emit] writes a decoder-stream instruction on our
 * QPACK decoder uni stream.
 */
class QpackDecoder(
    maxCapacity: Long,
    private val emit: suspend (QpackDecoderInstruction) -> Unit,
) {
    private val table = QpackDynamicTable(maxCapacity)

    // Mirrors table.insertCount; lets a blocked decodeSection await new inserts reactively.
    private val insertCount = MutableStateFlow(InsertCount.ZERO)

    // Guards table state: applyEncoderInstruction (the encoder-stream router coroutine) mutates it
    // while concurrent decodeSection calls (per-request coroutines on Dispatchers.Default) read it.
    // Never held across the blocking await in decodeSection — only around the brief mutate/decode.
    private val mutex = Mutex()

    /**
     * Our model of the peer encoder's **Known Received Count** — how many of our insertions it already
     * considers acknowledged (RFC 9204 §2.1.4).
     *
     * A Section Acknowledgment does not only acknowledge its stream: it *implicitly* acknowledges every
     * dynamic-table insertion up to that section's Required Insert Count. So the two decoder-stream
     * instructions we send both advance the peer's count, and an Insert Count Increment must only cover
     * insertions **not already covered that way** — the increment is a delta against this, never a flat
     * one-per-insert.
     *
     * Sending one per insert regardless double-counts, and the peer is required to kill the connection
     * for it (`QPACK_DECODER_STREAM_ERROR`, 0x202, "increases the Known Received Count beyond what the
     * encoder has sent"). Whether it fired depended purely on which of the two instructions we emitted
     * first: increment-then-ack is harmless, because the ack's jump to the Required Insert Count is then
     * a no-op — but ack-then-increment adds on top of a count the ack already advanced. Both emissions
     * happen off different coroutines (the encoder-stream router vs. a per-request decode), so the order
     * was a race, which is why this surfaced as a rare dynamic-QPACK-only flake rather than a hard break.
     */
    private var acknowledgedInsertCount = InsertCount.ZERO

    /**
     * Serializes decoder-stream emissions **together with** the [acknowledgedInsertCount] update.
     *
     * Held across the emit on purpose: the count is only correct if the order instructions reach the
     * wire is the order they were accounted for. Computing under a lock and emitting outside it would
     * reintroduce the same race one level down. Distinct from [mutex] — that one guards table state and
     * must never be held across I/O.
     *
     * Because it *is* held across a caller-supplied [emit], both acquisitions pass the current coroutine
     * as the mutex **owner**. An `emit` that re-entered this decoder would then fail immediately with
     * `IllegalStateException` instead of deadlocking silently on itself. Nothing does that today — the
     * lambda writes a QUIC stream — and this is what keeps it that way loudly rather than by convention.
     *
     * Deadlock is otherwise ruled out by lock ordering: [mutex] is always released before this is taken,
     * so no code path ever holds one while acquiring the other.
     */
    private val ackMutex = Mutex()

    /**
     * Tell the peer's encoder we have processed insertions up to [upTo], as a delta against what it
     * already knows ([acknowledgedInsertCount]). Emits nothing when a Section Acknowledgment has already
     * carried the count that far — an Increment of zero is itself a decoder-stream error (§4.4.3).
     */
    private suspend fun emitInsertCountIncrement(upTo: InsertCount) {
        ackMutex.withLock(currentCoroutineContext()[Job]) {
            // [InsertCount.advanceFrom], not `minus`: subtraction is partial, and [upTo] is captured
            // outside this lock — in [applyEncoderInstruction], under the table mutex — so by the time
            // this coroutine holds the lock, [acknowledgedInsertCount] may already have moved past it.
            // A Section Acknowledgment on another coroutine is enough, and so is a second peer encoder
            // stream, which nothing currently forbids. Subtracting first and testing the sign second
            // would let the peer decide whether a `require` fires. Null means already covered, which is
            // also why an Increment of zero — itself a decoder-stream error (§4.4.3) — is never emitted.
            //
            // Subtraction (here, inside advanceFrom) remains the ONLY route to an InsertCountDelta, so
            // the shape of #353's bug — handing InsertCountIncrement a flat `1` — still does not compile.
            val increment = upTo.advanceFrom(acknowledgedInsertCount) ?: return@withLock
            emit(QpackDecoderInstruction.InsertCountIncrement(increment))
            // Only after a successful write: a failed emit never reached the peer, so its count did not move.
            acknowledgedInsertCount = upTo
        }
    }

    /** Acknowledge [streamId]'s section, recording the insertions [requiredInsertCount] implicitly covers. */
    private suspend fun emitSectionAck(
        streamId: QuicStreamId,
        requiredInsertCount: InsertCount,
    ) {
        ackMutex.withLock(currentCoroutineContext()[Job]) {
            emit(QpackDecoderInstruction.SectionAck(streamId))
            if (requiredInsertCount > acknowledgedInsertCount) acknowledgedInsertCount = requiredInsertCount
        }
    }

    /** Current number of insertions into the decoder table (RFC 9204 §3.2.4) — for tests/diagnostics. */
    val insertCountValue: InsertCount get() = table.insertCount

    /**
     * Apply one encoder-stream instruction (RFC 9204 §4.3) to the decoder table, emitting an Insert
     * Count Increment for each successful insertion so the peer's encoder can advance its Known
     * Received Count (§4.4.3). Throws [Http3StreamException] with [Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR]
     * on an instruction that violates the table invariants (over-capacity, dangling reference).
     */
    suspend fun applyEncoderInstruction(instruction: QpackEncoderInstruction) {
        val totalInserts =
            mutex.withLock {
                when (instruction) {
                    is QpackEncoderInstruction.SetCapacity ->
                        if (!table.setCapacity(instruction.capacity)) {
                            throw Http3StreamException(Http3Violation.QpackSetCapacityExceedsMax(instruction.capacity))
                        }
                    is QpackEncoderInstruction.InsertWithNameRef -> {
                        val name =
                            if (instruction.isStatic) {
                                staticName(
                                    instruction.nameIndex,
                                )
                            } else {
                                relativeEntry(instruction.nameIndex).name
                            }
                        insertOrThrow(name, instruction.value)
                    }
                    is QpackEncoderInstruction.InsertWithLiteralName -> insertOrThrow(instruction.name, instruction.value)
                    is QpackEncoderInstruction.Duplicate -> {
                        val entry = relativeEntry(instruction.index)
                        insertOrThrow(entry.name, entry.value)
                    }
                }
                insertCount.value = table.insertCount
                table.insertCount
            }
        // Emit outside the table lock (it does stream I/O). The count is reported unconditionally and
        // [emitInsertCountIncrement] takes the DELTA against what the peer's encoder already knows, so a
        // SetCapacity — which inserts nothing — yields a zero delta and emits nothing. That is why there
        // is no "did it insert?" flag to carry out of the lock: the delta already answers it.
        emitInsertCountIncrement(totalInserts)
    }

    /**
     * Decode an encoded field section (RFC 9204 §4.5) off [buffer] for [streamId]. Reads the prefix,
     * blocks until the table has the Required Insert Count of entries, decodes every field line, and —
     * if the section referenced the dynamic table (RIC > 0) — emits a Section Acknowledgment.
     */
    suspend fun decodeSection(
        buffer: ReadBuffer,
        streamId: QuicStreamId,
        scratchPool: BufferPool?,
    ): List<QpackHeaderField> {
        // Any failure to decode a field section — a bad prefix, an out-of-range static/dynamic index,
        // a malformed prefixed integer, invalid Huffman, a string literal or varint that reads past the
        // (bounded) section buffer, or a non-UTF-8 string octet (which the buffer's readString rejects
        // on some platforms) — is a *connection* error of type QPACK_DECOMPRESSION_FAILED (RFC 9204
        // §2.2): the dynamic-table state desynchronizes irrecoverably. The leaf codecs raise the buffer
        // layer's DecodeException or a platform-specific buffer-underflow / decoding error; translate ANY
        // such wire-driven Throwable to the typed HTTP/3 error here so callers see one error currency.
        // Only CancellationException (the blocking await) propagates unchanged. The Section
        // Acknowledgment (stream I/O) is emitted only after a successful decode, OUTSIDE this catch, so a
        // write failure is never mistyped.
        val prefix: QpackPrefix
        val fields: List<QpackHeaderField>
        try {
            // Snapshot the insert count from the StateFlow (atomic) for the prefix reconstruction.
            prefix = QpackFieldSectionPrefix.decode(buffer, table.maxEntries, insertCount.value)
            // Block until enough entries have been inserted to resolve this section (§2.2.1) — OUTSIDE the
            // mutex, so the encoder-stream router can keep inserting (and unblock us). The
            // QPACK_BLOCKED_STREAMS limit (how many sections may block at once) is the peer encoder's
            // responsibility; here we simply wait for the inserts it promised via the Required Insert Count.
            if (prefix.requiredInsertCount > insertCount.value) {
                insertCount.first { it >= prefix.requiredInsertCount }
            }
            fields =
                mutex.withLock {
                    buildList { while (buffer.hasRemaining()) add(decodeFieldLine(buffer, prefix.base, scratchPool)) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw Http3StreamException(Http3Violation.QpackDecompressionFailed(e))
        }
        if (prefix.requiredInsertCount > InsertCount.ZERO) emitSectionAck(streamId, prefix.requiredInsertCount)
        return fields
    }

    /** Notify the peer's encoder that [streamId]'s outstanding section references are abandoned (§4.4.2). */
    suspend fun cancelStream(streamId: QuicStreamId) = emit(QpackDecoderInstruction.StreamCancellation(streamId))

    private fun decodeFieldLine(
        buffer: ReadBuffer,
        base: Long,
        scratchPool: BufferPool?,
    ): QpackHeaderField {
        val first = buffer.readByte().toInt() and 0xFF
        return when {
            // Indexed Field Line (§4.5.2): 1 T index:6 — T=1 static, T=0 dynamic (relative to Base).
            first and 0x80 != 0 -> {
                val index = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 6)
                if (first and 0x40 != 0) staticEntry(buffer, index) else dynamicRelativeToBase(buffer, base, index)
            }
            // Literal with Name Reference (§4.5.4): 0 1 N T nameIndex:4 — T=1 static, T=0 dynamic.
            first and 0x40 != 0 -> {
                val nameIndex = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 4)
                val name =
                    if (first and 0x10 != 0) {
                        staticEntry(buffer, nameIndex).name
                    } else {
                        dynamicRelativeToBase(buffer, base, nameIndex).name
                    }
                QpackHeaderField(name, QpackStringLiteral.read(buffer, prefixBits = 7, "QpackFieldSection.value", scratchPool))
            }
            // Literal with Literal Name (§4.5.6): 0 0 1 N H nameLen:3.
            first and 0x20 != 0 -> {
                val name = QpackStringLiteral.readFromFirstByte(buffer, first, prefixBits = 3, "QpackFieldSection.name", scratchPool)
                QpackHeaderField(name, QpackStringLiteral.read(buffer, prefixBits = 7, "QpackFieldSection.value", scratchPool))
            }
            // Indexed Field Line with Post-Base Index (§4.5.3): 0 0 0 1 index:4 — absolute = Base + index.
            first and 0x10 != 0 -> {
                val index = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 4)
                dynamicPostBase(buffer, base, index)
            }
            // Literal with Post-Base Name Reference (§4.5.5): 0 0 0 0 N nameIndex:3.
            else -> {
                val nameIndex = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 3)
                val name = dynamicPostBaseEntry(buffer, base, nameIndex).name
                QpackHeaderField(name, QpackStringLiteral.read(buffer, prefixBits = 7, "QpackFieldSection.value", scratchPool))
            }
        }
    }

    // --- dynamic-table resolution (field section, relative to Base) ---

    private fun dynamicRelativeToBase(
        buffer: ReadBuffer,
        base: Long,
        relativeIndex: Long,
    ): QpackHeaderField = liveEntry(buffer, base - 1 - relativeIndex)

    private fun dynamicPostBase(
        buffer: ReadBuffer,
        base: Long,
        postBaseIndex: Long,
    ): QpackHeaderField = liveEntry(buffer, base + postBaseIndex)

    private fun dynamicPostBaseEntry(
        buffer: ReadBuffer,
        base: Long,
        postBaseIndex: Long,
    ): QpackHeaderField = liveEntry(buffer, base + postBaseIndex)

    private fun liveEntry(
        buffer: ReadBuffer,
        absoluteIndex: Long,
    ): QpackHeaderField {
        val entry =
            table.getByAbsolute(absoluteIndex)
                ?: throw DecodeException(
                    fieldPath = "QpackFieldSection.dynamicIndex",
                    bufferPosition = buffer.position(),
                    expected = "a live dynamic-table entry",
                    actual = "absolute index $absoluteIndex (evicted or never inserted)",
                )
        return QpackHeaderField(entry.name, entry.value)
    }

    private fun staticEntry(
        buffer: ReadBuffer,
        index: Long,
    ): QpackHeaderField {
        if (index < 0 || index >= QpackStaticTable.size) {
            throw DecodeException(
                fieldPath = "QpackFieldSection.staticIndex",
                bufferPosition = buffer.position(),
                expected = "a static-table index in 0..${QpackStaticTable.size - 1}",
                actual = index.toString(),
            )
        }
        return QpackStaticTable.entry(index.toInt())
    }

    // --- encoder-instruction helpers (relative to the most recent insert) ---

    /** Resolve an encoder-stream relative index (RFC 9204 §3.2.5): absolute = insertCount - 1 - index. */
    private fun relativeEntry(relativeIndex: Long): QpackDynamicTable.Entry =
        table.getByAbsolute(table.insertCount.value - 1 - relativeIndex)
            ?: throw Http3StreamException(Http3Violation.QpackEncoderRelativeIndexMissing(relativeIndex))

    private fun staticName(index: Long): String {
        if (index < 0 || index >= QpackStaticTable.size) {
            throw Http3StreamException(Http3Violation.QpackStaticNameIndexOutOfRange(index))
        }
        return QpackStaticTable.entry(index.toInt()).name
    }

    private fun insertOrThrow(
        name: String,
        value: String,
    ) {
        if (table.insert(name, value) == null) {
            throw Http3StreamException(
                Http3Violation.QpackInsertExceedsCapacity(qpackEntrySize(name, value), table.capacity),
            )
        }
    }
}
