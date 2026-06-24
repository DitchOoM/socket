package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.pool.BufferPool
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
    private val insertCount = MutableStateFlow(0L)

    // Guards table state: applyEncoderInstruction (the encoder-stream router coroutine) mutates it
    // while concurrent decodeSection calls (per-request coroutines on Dispatchers.Default) read it.
    // Never held across the blocking await in decodeSection — only around the brief mutate/decode.
    private val mutex = Mutex()

    /** Current number of insertions into the decoder table (RFC 9204 §3.2.4) — for tests/diagnostics. */
    val insertCountValue: Long get() = table.insertCount

    /**
     * Apply one encoder-stream instruction (RFC 9204 §4.3) to the decoder table, emitting an Insert
     * Count Increment for each successful insertion so the peer's encoder can advance its Known
     * Received Count (§4.4.3). Throws [Http3StreamException] with [Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR]
     * on an instruction that violates the table invariants (over-capacity, dangling reference).
     */
    suspend fun applyEncoderInstruction(instruction: QpackEncoderInstruction) {
        val inserted =
            mutex.withLock {
                when (instruction) {
                    is QpackEncoderInstruction.SetCapacity -> {
                        if (!table.setCapacity(instruction.capacity)) {
                            throw encoderStreamError("Set Dynamic Table Capacity ${instruction.capacity} exceeds the advertised maximum")
                        }
                        false // no insert
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
                        true
                    }
                    is QpackEncoderInstruction.InsertWithLiteralName -> {
                        insertOrThrow(instruction.name, instruction.value)
                        true
                    }
                    is QpackEncoderInstruction.Duplicate -> {
                        val entry = relativeEntry(instruction.index)
                        insertOrThrow(entry.name, entry.value)
                        true
                    }
                }.also { if (it) insertCount.value = table.insertCount }
            }
        // Emit outside the lock (it does stream I/O); a per-insert increment lets the peer's encoder
        // advance its Known Received Count (§4.4.3).
        if (inserted) emit(QpackDecoderInstruction.InsertCountIncrement(1))
    }

    /**
     * Decode an encoded field section (RFC 9204 §4.5) off [buffer] for [streamId]. Reads the prefix,
     * blocks until the table has the Required Insert Count of entries, decodes every field line, and —
     * if the section referenced the dynamic table (RIC > 0) — emits a Section Acknowledgment.
     */
    suspend fun decodeSection(
        buffer: ReadBuffer,
        streamId: Long,
        scratchPool: BufferPool?,
    ): List<QpackHeaderField> {
        // Any failure to decode a field section — a bad prefix, an out-of-range static/dynamic index,
        // a malformed prefixed integer, invalid Huffman, a string literal past the buffer — is a
        // *connection* error of type QPACK_DECOMPRESSION_FAILED (RFC 9204 §2.2): the dynamic-table
        // state desynchronizes irrecoverably. The leaf codecs throw the buffer layer's DecodeException;
        // translate it to the typed HTTP/3 error at this boundary so callers see one error currency.
        try {
            // Snapshot the insert count from the StateFlow (atomic) for the prefix reconstruction.
            val prefix = QpackFieldSectionPrefix.decode(buffer, table.maxEntries, insertCount.value)
            // Block until enough entries have been inserted to resolve this section (§2.2.1) — OUTSIDE the
            // mutex, so the encoder-stream router can keep inserting (and unblock us). The
            // QPACK_BLOCKED_STREAMS limit (how many sections may block at once) is the peer encoder's
            // responsibility; here we simply wait for the inserts it promised via the Required Insert Count.
            if (prefix.requiredInsertCount > insertCount.value) {
                insertCount.first { it >= prefix.requiredInsertCount }
            }
            val fields =
                mutex.withLock {
                    buildList { while (buffer.hasRemaining()) add(decodeFieldLine(buffer, prefix.base, scratchPool)) }
                }
            if (prefix.requiredInsertCount > 0) emit(QpackDecoderInstruction.SectionAck(streamId))
            return fields
        } catch (e: DecodeException) {
            throw Http3StreamException(
                "QPACK field-section decode failed: ${e.message}",
                Http3ErrorCode.QPACK_DECOMPRESSION_FAILED,
            )
        }
    }

    /** Notify the peer's encoder that [streamId]'s outstanding section references are abandoned (§4.4.2). */
    suspend fun cancelStream(streamId: Long) = emit(QpackDecoderInstruction.StreamCancellation(streamId))

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
        table.getByAbsolute(table.insertCount - 1 - relativeIndex)
            ?: throw encoderStreamError("encoder-stream relative index $relativeIndex references a missing entry")

    private fun staticName(index: Long): String {
        if (index < 0 || index >= QpackStaticTable.size) {
            throw encoderStreamError("static name index $index out of range")
        }
        return QpackStaticTable.entry(index.toInt()).name
    }

    private fun insertOrThrow(
        name: String,
        value: String,
    ) {
        if (table.insert(name, value) == null) {
            throw encoderStreamError("inserted entry (size ${qpackEntrySize(name, value)}) exceeds table capacity ${table.capacity}")
        }
    }

    private fun encoderStreamError(message: String): Http3StreamException =
        Http3StreamException("QPACK encoder stream: $message", Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR)
}
