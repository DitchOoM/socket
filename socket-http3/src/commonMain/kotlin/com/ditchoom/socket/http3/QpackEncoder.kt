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
 * **Strategy (always non-blocking, always spec-legal):** a field that hits the static or an
 * *acknowledged* dynamic entry is encoded as an indexed line; an unseen field is inserted into the
 * dynamic table for *future* reuse but encoded as a literal in the current section, so the section
 * never references an unacknowledged entry and never blocks.
 *
 * **Eviction (RFC 9204 §2.1.3).** Inserting into a full table evicts its oldest entries. To stay
 * correct the encoder must never evict an entry an outstanding (un-acknowledged) field section still
 * references — otherwise the peer's decoder, which evicts in lockstep, would fail to resolve that
 * reference. We therefore reference-count: [entryRefCount] tracks, per absolute index, how many
 * emitted-but-unacked sections reference it (plus the section currently being built), and an insert
 * may only evict entries with a zero count ([QpackDynamicTable.insertIfEvictable]). A Section
 * Acknowledgment releases its section's references; a Stream Cancellation releases the stream's. This
 * keeps the table churning as headers evolve (unlike a never-evict table that freezes once full) while
 * preserving the non-blocking invariant.
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

    // Per-stream FIFO of sections we've emitted but not yet seen acked: each carries its Required
    // Insert Count (to advance the Known Received Count on ack) and the absolute indices it references
    // (to release their entry reference counts on ack/cancel). Sections that reference no dynamic
    // entry (RIC 0) are never recorded — the decoder never acks them.
    private val outstandingSections = mutableMapOf<Long, ArrayDeque<OutstandingSection>>()

    // Absolute index → number of outstanding sections referencing it. An entry present here (count ≥ 1)
    // is "pinned": an insert must not evict it (RFC 9204 §2.1.3). Absent ⇒ count 0 ⇒ evictable.
    private val entryRefCount = HashMap<Long, Int>()

    private class OutstandingSection(
        val requiredInsertCount: Long,
        val referencedAbsolutes: List<Long>,
    )

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
            // Absolute indices this section references. Held locally (not yet committed to entryRefCount)
            // so a throw mid-build leaks no pins; planField also consults it so an insert later in this
            // same section never evicts an entry an earlier line already referenced.
            val sectionRefs = ArrayList<Long>()
            val ops = ArrayList<Op>(fields.size)
            var maxReferencedAbsolute = -1L
            for (field in fields) {
                val op = planField(field, sectionRefs)
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
                // Commit the pins now that the section is fully built and will be sent.
                for (absolute in sectionRefs) entryRefCount[absolute] = (entryRefCount[absolute] ?: 0) + 1
                outstandingSections
                    .getOrPut(streamId) { ArrayDeque() }
                    .addLast(OutstandingSection(requiredInsertCount, sectionRefs))
            }
            buffer
        }

    /**
     * Plan one field, performing the dynamic-table insertion (and the encoder-stream emit) as a side
     * effect when the field is new and there is room. Returns the representation to write for *this*
     * occurrence — never a reference to a just-inserted (unacknowledged) entry.
     */
    private suspend fun planField(
        field: QpackHeaderField,
        sectionRefs: MutableList<Long>,
    ): Op {
        QpackStaticTable.findExact(field.name, field.value)?.let { return Op.StaticIndexed(it.toLong()) }

        val dynamicExact = table.findExact(field.name, field.value)
        if (dynamicExact != null && dynamicExact < knownReceivedCount) {
            sectionRefs += dynamicExact // pin: a later insert in this section must not evict it
            return Op.DynamicIndexed(dynamicExact)
        }

        tryInsertForFutureReuse(field, sectionRefs)
        // Encode this occurrence as a literal regardless of whether we just inserted (non-blocking).
        val staticName = QpackStaticTable.findName(field.name)
        return if (staticName != null) Op.LiteralNameRef(staticName.toLong(), field.value) else Op.LiteralName(field.name, field.value)
    }

    /**
     * Try to add [field] to the dynamic table for future reuse, evicting oldest entries if needed but
     * never one that an outstanding section — or the section currently being built ([sectionRefs]) —
     * still references. Emits the encoder-stream insert only when the table actually accepted it. Insert
     * happens BEFORE the emit so our insert count already reflects the entry when the decoder's Insert
     * Count Increment feeds back (it must never exceed our inserts).
     */
    private suspend fun tryInsertForFutureReuse(
        field: QpackHeaderField,
        sectionRefs: List<Long>,
    ) {
        val inserted =
            table.insertIfEvictable(field.name, field.value) { entry ->
                !entryRefCount.containsKey(entry.absoluteIndex) && entry.absoluteIndex !in sectionRefs
            }
        if (inserted == null) return // doesn't fit, or fitting would evict a still-referenced entry
        val staticName = QpackStaticTable.findName(field.name)
        if (staticName != null) {
            emit(QpackEncoderInstruction.InsertWithNameRef(staticName.toLong(), isStatic = true, value = field.value))
        } else {
            emit(QpackEncoderInstruction.InsertWithLiteralName(field.name, field.value))
        }
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
                    val section =
                        outstandingSections[instruction.streamId]?.removeFirstOrNull()
                            ?: throw decoderStreamError(
                                "Section Acknowledgment for stream ${instruction.streamId} with no outstanding section",
                            )
                    for (absolute in section.referencedAbsolutes) releaseRef(absolute)
                    if (section.requiredInsertCount > knownReceivedCount) knownReceivedCount = section.requiredInsertCount
                }
                is QpackDecoderInstruction.StreamCancellation ->
                    outstandingSections.remove(instruction.streamId)?.forEach { section ->
                        section.referencedAbsolutes.forEach { releaseRef(it) }
                    }
            }
        }
    }

    /** Drop one reference to the entry at [absolute]; once the count hits 0 it becomes evictable. */
    private fun releaseRef(absolute: Long) {
        val count = entryRefCount[absolute] ?: return
        if (count <= 1) entryRefCount.remove(absolute) else entryRefCount[absolute] = count - 1
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
