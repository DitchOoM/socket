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
 * Received Count so it knows which entries the peer has confirmed.
 *
 * **Strategy:** a field that hits the static or an *acknowledged* dynamic entry is encoded as an
 * indexed line and never blocks. An unseen field is inserted into the dynamic table; whether *this*
 * occurrence is then encoded as an index or a literal depends on the blocked-stream budget:
 *  - **Non-blocking (default / no budget):** the current occurrence is encoded as a literal and a
 *    *later* section references the entry once the peer acknowledges the insert. Never blocks.
 *  - **Blocking (within [peerMaxBlockedStreams]):** the current occurrence references the just-inserted
 *    (or an existing unacknowledged) entry directly — the section's Required Insert Count then exceeds
 *    the Known Received Count, so the peer's decoder may briefly block until our encoder-stream insert
 *    arrives (RFC 9204 §2.1.2). This compresses even first occurrences. The encoder caps the number of
 *    streams it lets become blocked at the peer's advertised `SETTINGS_QPACK_BLOCKED_STREAMS`
 *    ([peerMaxBlockedStreams]); once that many streams are blocked it falls back to the literal path.
 *
 * **Draining (RFC 9204 §2.1.3).** When a matched entry is in the table's draining region (the oldest,
 * about-to-be-evicted entries) and the table is under pressure, the encoder — if it may block — refreshes
 * it with a `Duplicate` (a cheap encoder-stream index, no value resend) and references the fresh copy, so
 * the old copy stays evictable instead of being pinned by the reference.
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
 * [peerMaxCapacity] is the peer's `SETTINGS_QPACK_MAX_TABLE_CAPACITY`; [peerMaxBlockedStreams] is its
 * `SETTINGS_QPACK_BLOCKED_STREAMS` (0 ⇒ the encoder stays strictly non-blocking); [emit] writes an
 * encoder-stream instruction on our QPACK encoder uni stream.
 */
class QpackEncoder(
    peerMaxCapacity: Long,
    private val peerMaxBlockedStreams: Long = 0,
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
            // One blocking decision per section: referencing unacknowledged entries makes the whole
            // section block on the peer's decoder, but every such reference still costs only this one
            // stream against the peer's blocked-stream budget.
            val canBlock = canBlockStream(streamId)
            for (field in fields) {
                val op = planField(field, sectionRefs, canBlock)
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
     * effect when the field is new and there is room. When [canBlock] is true this may reference a
     * just-inserted or otherwise-unacknowledged entry (a blocking reference); otherwise it returns a
     * literal for unacknowledged entries so the section never blocks.
     */
    private suspend fun planField(
        field: QpackHeaderField,
        sectionRefs: MutableList<Long>,
        canBlock: Boolean,
    ): Op {
        QpackStaticTable.findExact(field.name, field.value)?.let { return Op.StaticIndexed(it.toLong()) }

        val dynamicExact = table.findExact(field.name, field.value)
        if (dynamicExact != null) {
            // The match is in the draining region (about to be evicted) and the table is under pressure:
            // refresh it via a cheap Duplicate (encoder-stream index, no value resend) and reference the
            // fresh copy, so the old one stays evictable instead of being pinned (RFC 9204 §2.1.3). Only
            // worthwhile with blocked-stream budget, since the fresh copy is unacknowledged.
            if (canBlock && table.isDraining(dynamicExact) && !table.canInsertWithoutEviction(field.name, field.value)) {
                val refreshed = tryDuplicate(dynamicExact, sectionRefs)
                if (refreshed != null) {
                    sectionRefs += refreshed
                    return Op.DynamicIndexed(refreshed)
                }
            }
            // Reference it when it's acknowledged (never blocks) or when blocked-stream budget lets us
            // risk a reference the peer may not have processed yet (RFC 9204 §2.1.2). A pin keeps a later
            // insert in this same section from evicting it.
            if (dynamicExact < knownReceivedCount || canBlock) {
                sectionRefs += dynamicExact
                return Op.DynamicIndexed(dynamicExact)
            }
            // Present but unacknowledged and out of budget: literal this time (a later section references
            // it once acked). It already exists, so don't insert a duplicate.
            return literalFor(field)
        }

        // Not in either table: insert for future reuse. With budget, reference the fresh insert now (the
        // section blocks until our encoder-stream insert reaches the peer); otherwise literal this time.
        val inserted = tryInsertForFutureReuse(field, sectionRefs)
        if (inserted != null && canBlock) {
            sectionRefs += inserted
            return Op.DynamicIndexed(inserted)
        }
        return literalFor(field)
    }

    /** The literal representation of [field] — name-reference when the static table has the name, else fully literal. */
    private fun literalFor(field: QpackHeaderField): Op {
        val staticName = QpackStaticTable.findName(field.name)
        return if (staticName != null) {
            Op.LiteralNameRef(staticName.toLong(), field.value)
        } else {
            Op.LiteralName(field.name, field.value)
        }
    }

    /**
     * Whether a section on [streamId] may include blocking references. True only when the peer permits
     * blocked streams ([peerMaxBlockedStreams] > 0) and either this stream is already counted as blocked
     * (so another blocking reference adds no new blocked stream) or we are still under the peer's budget.
     * A stream counts as blocked while it has an outstanding section whose Required Insert Count exceeds
     * the Known Received Count — i.e. one the peer's decoder might not yet be able to resolve.
     */
    private fun canBlockStream(streamId: Long): Boolean {
        if (peerMaxBlockedStreams <= 0) return false
        var blocked = 0
        var thisStreamAlreadyBlocking = false
        for ((id, sections) in outstandingSections) {
            if (sections.any { it.requiredInsertCount > knownReceivedCount }) {
                blocked++
                if (id == streamId) thisStreamAlreadyBlocking = true
            }
        }
        return thisStreamAlreadyBlocking || blocked < peerMaxBlockedStreams
    }

    /**
     * Try to add [field] to the dynamic table for future reuse, evicting oldest entries if needed but
     * never one that an outstanding section — or the section currently being built ([sectionRefs]) —
     * still references. Emits the encoder-stream insert only when the table actually accepted it. Insert
     * happens BEFORE the emit so our insert count already reflects the entry when the decoder's Insert
     * Count Increment feeds back (it must never exceed our inserts). Returns the new entry's absolute
     * index, or null if it didn't fit / fitting would evict a still-referenced entry.
     */
    private suspend fun tryInsertForFutureReuse(
        field: QpackHeaderField,
        sectionRefs: List<Long>,
    ): Long? {
        val inserted =
            table.insertIfEvictable(field.name, field.value) { entry ->
                !entryRefCount.containsKey(entry.absoluteIndex) && entry.absoluteIndex !in sectionRefs
            } ?: return null
        val staticName = QpackStaticTable.findName(field.name)
        if (staticName != null) {
            emit(QpackEncoderInstruction.InsertWithNameRef(staticName.toLong(), isStatic = true, value = field.value))
        } else {
            emit(QpackEncoderInstruction.InsertWithLiteralName(field.name, field.value))
        }
        return inserted
    }

    /**
     * Refresh a still-useful but **draining** entry (RFC 9204 §2.1.3): insert a fresh copy of the entry
     * at [absoluteIndex] via a Duplicate instruction — which references the source by its encoder-stream
     * relative index, so the value is not resent — then the old copy can drain out. Evicts oldest entries
     * as needed but never one that an outstanding section ([entryRefCount]) or the section being built
     * ([sectionRefs]) still references. Returns the fresh copy's absolute index, or null if the duplicate
     * can't be inserted safely.
     *
     * The relative index is captured BEFORE the insert (which advances `insertCount`); on the decoder side
     * the Duplicate is read against the same pre-insert state and the source entry is read before its own
     * duplicate-insert evicts it, so encoder and decoder stay in lockstep.
     */
    private suspend fun tryDuplicate(
        absoluteIndex: Long,
        sectionRefs: List<Long>,
    ): Long? {
        val entry = table.getByAbsolute(absoluteIndex) ?: return null
        val relativeIndex = table.insertCount - 1 - absoluteIndex
        val inserted =
            table.insertIfEvictable(entry.name, entry.value) { e ->
                !entryRefCount.containsKey(e.absoluteIndex) && e.absoluteIndex !in sectionRefs
            } ?: return null
        emit(QpackEncoderInstruction.Duplicate(relativeIndex))
        return inserted
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
                        throw Http3StreamException(Http3Violation.QpackInsertCountIncrementPastInserts(instruction.increment))
                    }
                    knownReceivedCount = updated
                }
                is QpackDecoderInstruction.SectionAck -> {
                    val section =
                        outstandingSections[instruction.streamId]?.removeFirstOrNull()
                            ?: throw Http3StreamException(
                                Http3Violation.QpackSectionAckWithoutOutstanding(instruction.streamId),
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
