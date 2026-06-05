package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stateful QPACK **encoder** for one connection (RFC 9204): compresses outbound field sections
 * using the static table plus a dynamic table it grows by sending instructions on our QPACK encoder
 * stream ([emit]). Tracks the decoder's acknowledgments ([processDecoderInstruction]) as a Known
 * Received Count so it only *references* entries the peer has confirmed — keeping every emitted
 * section non-blocking.
 *
 * **Strategy (deliberately conservative, always spec-legal):** a field that hits the static or an
 * *acknowledged* dynamic entry is encoded as an indexed line; an unseen field is inserted into the
 * dynamic table for *future* reuse (when there's room without eviction) but encoded as a literal in
 * the current section, so the section never references an unacknowledged entry and never blocks. The
 * table is never evicted (inserts stop when full), so an outstanding section can never reference an
 * evicted entry. This trades maximal compression for simplicity and correctness.
 *
 * [peerMaxCapacity] is the peer's `SETTINGS_QPACK_MAX_TABLE_CAPACITY`; [emit] writes an encoder-stream
 * instruction on our QPACK encoder uni stream.
 */
class QpackEncoder(
    peerMaxCapacity: Long,
    private val emit: suspend (QpackEncoderInstruction) -> Unit,
) {
    private val table = QpackDynamicTable(peerMaxCapacity)
    private var knownReceivedCount = 0L

    // Serializes the stateful encode path: concurrent encodeSection (per-request coroutines) and
    // processDecoderInstruction (the decoder-stream router) must not interleave on the table/KRC.
    private val mutex = Mutex()

    // Per-stream FIFO of the Required Insert Counts of sections we've emitted but not yet seen acked.
    private val outstandingSectionRic = mutableMapOf<Long, ArrayDeque<Long>>()

    /** Current dynamic-table insert count — for tests/diagnostics. */
    val insertCountValue: Long get() = table.insertCount

    /**
     * Raise the dynamic table capacity to [capacity] (≤ [peerMaxCapacity]) and tell the peer's decoder
     * via a Set Dynamic Table Capacity instruction (RFC 9204 §4.3.1). Call once after the peer's
     * SETTINGS are known; a capacity of 0 leaves the encoder static-only.
     */
    suspend fun setCapacity(capacity: Long) {
        mutex.withLock {
            if (capacity == 0L || !table.setCapacity(capacity)) return
            emit(QpackEncoderInstruction.SetCapacity(capacity))
        }
    }

    /**
     * Encode [fields] for [streamId] into a freshly pool-allocated [ReadBuffer] (the HEADERS frame
     * payload), emitting any dynamic-table insert instructions on the encoder stream first. Field
     * order is preserved (HTTP semantics). The caller owns the returned buffer (`freeIfNeeded()`).
     */
    suspend fun encodeSection(
        fields: List<QpackHeaderField>,
        streamId: Long,
        pool: BufferPool,
    ): ReadBuffer =
        mutex.withLock {
            val ops = ArrayList<Op>(fields.size)
            var maxReferencedAbsolute = -1L
            for (field in fields) {
                val op = planField(field)
                if (op is Op.DynamicIndexed) maxReferencedAbsolute = maxOf(maxReferencedAbsolute, op.absoluteIndex)
                ops += op
            }
            val requiredInsertCount = if (maxReferencedAbsolute < 0) 0L else maxReferencedAbsolute + 1
            val base = requiredInsertCount // all dynamic references are pre-Base (relative)

            val size = prefixUpperBound + ops.sumOf { it.maxSize(base) }
            val buffer = pool.allocate(size)
            QpackFieldSectionPrefix.encode(buffer, requiredInsertCount, base, table.maxEntries)
            for (op in ops) op.encode(buffer, base)
            buffer.resetForRead()

            if (requiredInsertCount > 0) {
                outstandingSectionRic.getOrPut(streamId) { ArrayDeque() }.addLast(requiredInsertCount)
            }
            buffer
        }

    /**
     * Plan one field, performing the dynamic-table insertion (and the encoder-stream emit) as a side
     * effect when the field is new and there is room. Returns the representation to write for *this*
     * occurrence — never a reference to a just-inserted (unacknowledged) entry.
     */
    private suspend fun planField(field: QpackHeaderField): Op {
        QpackStaticTable.findExact(field.name, field.value)?.let { return Op.StaticIndexed(it.toLong()) }

        val dynamicExact = table.findExact(field.name, field.value)
        if (dynamicExact != null && dynamicExact < knownReceivedCount) {
            return Op.DynamicIndexed(dynamicExact)
        }

        if (table.canInsertWithoutEviction(field.name, field.value)) {
            // Insert into our table BEFORE emitting, so our insert count already reflects the entry
            // when the decoder's Insert Count Increment feeds back (it must not exceed our inserts).
            table.insert(field.name, field.value)
            val staticName = QpackStaticTable.findName(field.name)
            if (staticName != null) {
                emit(QpackEncoderInstruction.InsertWithNameRef(staticName.toLong(), isStatic = true, value = field.value))
            } else {
                emit(QpackEncoderInstruction.InsertWithLiteralName(field.name, field.value))
            }
        }
        // Encode this occurrence as a literal regardless of whether we just inserted (non-blocking).
        val staticName = QpackStaticTable.findName(field.name)
        return if (staticName != null) Op.LiteralNameRef(staticName.toLong(), field.value) else Op.LiteralName(field.name, field.value)
    }

    /**
     * Apply a decoder-stream instruction (RFC 9204 §4.4) so the Known Received Count tracks what the
     * peer's decoder has processed. Throws [Http3StreamException] with
     * [Http3ErrorCode.QPACK_DECODER_STREAM_ERROR] on an inconsistent acknowledgment.
     */
    suspend fun processDecoderInstruction(instruction: QpackDecoderInstruction) {
        mutex.withLock {
            when (instruction) {
                is QpackDecoderInstruction.InsertCountIncrement -> {
                    val updated = knownReceivedCount + instruction.increment
                    if (instruction.increment <= 0 || updated > table.insertCount) {
                        throw decoderStreamError("Insert Count Increment ${instruction.increment} pushes Known Received Count past inserts")
                    }
                    knownReceivedCount = updated
                }
                is QpackDecoderInstruction.SectionAck -> {
                    val ric =
                        outstandingSectionRic[instruction.streamId]?.removeFirstOrNull()
                            ?: throw decoderStreamError(
                                "Section Acknowledgment for stream ${instruction.streamId} with no outstanding section",
                            )
                    if (ric > knownReceivedCount) knownReceivedCount = ric
                }
                is QpackDecoderInstruction.StreamCancellation ->
                    outstandingSectionRic.remove(instruction.streamId)
            }
        }
    }

    private fun decoderStreamError(message: String): Http3StreamException =
        Http3StreamException("QPACK decoder stream: $message", Http3ErrorCode.QPACK_DECODER_STREAM_ERROR)

    // Prefix upper bound: RIC (≤ ~9 bytes for a 63-bit varint at an 8-bit prefix) + Base (≤ ~9).
    private val prefixUpperBound = 20

    /** A planned field-line representation; sizes/encodes itself given the section [base]. */
    private sealed interface Op {
        fun maxSize(base: Long): Int

        fun encode(
            buffer: WriteBuffer,
            base: Long,
        )

        data class StaticIndexed(
            val index: Long,
        ) : Op {
            override fun maxSize(base: Long) = QpackPrefixedInteger.encodedLength(index, prefixBits = 6)

            override fun encode(
                buffer: WriteBuffer,
                base: Long,
            ) = QpackPrefixedInteger.encode(buffer, index, prefixBits = 6, firstByteFlags = 0xC0)
        }

        data class DynamicIndexed(
            val absoluteIndex: Long,
        ) : Op {
            private fun relative(base: Long) = base - 1 - absoluteIndex

            override fun maxSize(base: Long) = QpackPrefixedInteger.encodedLength(relative(base), prefixBits = 6)

            override fun encode(
                buffer: WriteBuffer,
                base: Long,
            ) = QpackPrefixedInteger.encode(buffer, relative(base), prefixBits = 6, firstByteFlags = 0x80)
        }

        data class LiteralNameRef(
            val staticNameIndex: Long,
            val value: String,
        ) : Op {
            override fun maxSize(base: Long) =
                QpackPrefixedInteger.encodedLength(staticNameIndex, prefixBits = 4) + QpackStringLiteral.size(value, prefixBits = 7)

            override fun encode(
                buffer: WriteBuffer,
                base: Long,
            ) {
                // 0 1 N T nameIndex:4 with N=0, T=1 (static name reference) → flags 0x50.
                QpackPrefixedInteger.encode(buffer, staticNameIndex, prefixBits = 4, firstByteFlags = 0x50)
                QpackStringLiteral.write(buffer, value, prefixBits = 7)
            }
        }

        data class LiteralName(
            val name: String,
            val value: String,
        ) : Op {
            override fun maxSize(base: Long) =
                QpackStringLiteral.size(name, prefixBits = 3) + QpackStringLiteral.size(value, prefixBits = 7)

            override fun encode(
                buffer: WriteBuffer,
                base: Long,
            ) {
                // 0 0 1 N H nameLen:3 with N=0 → flags 0x20 (H supplied by QpackStringLiteral).
                QpackStringLiteral.write(buffer, name, prefixBits = 3, flags = 0x20)
                QpackStringLiteral.write(buffer, value, prefixBits = 7)
            }
        }
    }
}
