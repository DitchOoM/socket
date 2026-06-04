package com.ditchoom.socket.http3

/**
 * The QPACK dynamic table (RFC 9204 §3.2): an insertion-ordered set of header field entries shared
 * by an encoder and the peer's decoder. This is the raw data structure — one instance per direction
 * (the client's encoder table mirrors the server's decoder; the client's decoder table is built from
 * the server's encoder-stream instructions). The stateful [QpackEncoder]/[QpackDecoder] layer the
 * protocol on top.
 *
 * **Indexing (RFC 9204 §3.2.4):** every insertion gets a monotonically increasing *absolute index*
 * starting at 0; [insertCount] is the number of insertions so far (the absolute index the next
 * insert will take), and the newest live entry has absolute index `insertCount - 1`. Evicted entries
 * keep their absolute indices "spent" — lookups for them return null.
 *
 * **Size accounting (RFC 9204 §3.2.1):** an entry's size is `name + value` octets + 32; [size] is the
 * sum over live entries and must never exceed [capacity]. [capacity] is set at runtime via
 * [setCapacity] (the Set Dynamic Table Capacity instruction) and is bounded by [maxCapacity] — the
 * `SETTINGS_QPACK_MAX_TABLE_CAPACITY` ceiling negotiated for this direction. The table starts at
 * capacity 0 (empty, unusable) until the encoder raises it.
 */
class QpackDynamicTable(
    /** The `SETTINGS_QPACK_MAX_TABLE_CAPACITY` ceiling for this direction; [capacity] may not exceed it. */
    val maxCapacity: Long,
) {
    /** A live dynamic-table entry with its absolute index (RFC 9204 §3.2.4). */
    data class Entry(
        val absoluteIndex: Long,
        val name: String,
        val value: String,
    ) {
        val size: Long = qpackEntrySize(name, value)
    }

    private val entries = ArrayDeque<Entry>() // oldest (lowest absolute index) at the front

    private var _capacity = 0L
    private var _insertCount = 0L
    private var _size = 0L

    /** Current maximum table size in octets (≤ [maxCapacity]); 0 until [setCapacity] raises it. */
    val capacity: Long get() = _capacity

    /** Total insertions so far — the absolute index the next inserted entry will take (RFC 9204 §3.2.4). */
    val insertCount: Long get() = _insertCount

    /** Current table size in octets (sum of live [Entry.size]); always ≤ [capacity]. */
    val size: Long get() = _size

    /** Number of live entries. */
    val entryCount: Int get() = entries.size

    /**
     * `MaxEntries = floor(maxCapacity / 32)` (RFC 9204 §4.5.1.1) — the constant used to wrap the
     * Required Insert Count in the field-section prefix. Based on the negotiated ceiling, not [capacity].
     */
    val maxEntries: Long get() = maxCapacity / QPACK_ENTRY_OVERHEAD

    /**
     * Apply a Set Dynamic Table Capacity instruction (RFC 9204 §3.2.3 / §4.3.1). Evicts oldest entries
     * until [size] ≤ [newCapacity]. Returns false if [newCapacity] exceeds [maxCapacity] (a protocol
     * error the caller maps to QPACK_ENCODER_STREAM_ERROR); the table is left unchanged in that case.
     */
    fun setCapacity(newCapacity: Long): Boolean {
        if (newCapacity < 0 || newCapacity > maxCapacity) return false
        _capacity = newCapacity
        evictToFit(0)
        return true
    }

    /**
     * Insert (name, value) as the newest entry, evicting oldest entries as needed (RFC 9204 §3.2.2).
     * Returns the new entry's absolute index, or null if it can never fit ([size] of the entry exceeds
     * [capacity]) — a protocol error for the caller. Eviction here is unconditional; the caller
     * ([QpackEncoder]) is responsible for not inserting when it would evict a still-referenced entry.
     */
    fun insert(
        name: String,
        value: String,
    ): Long? {
        val entrySize = qpackEntrySize(name, value)
        if (entrySize > _capacity) return null
        evictToFit(entrySize)
        val absolute = _insertCount
        entries.addLast(Entry(absolute, name, value))
        _size += entrySize
        _insertCount++
        return absolute
    }

    private fun evictToFit(incoming: Long) {
        while (_size + incoming > _capacity && entries.isNotEmpty()) {
            _size -= entries.removeFirst().size
        }
    }

    /** The live entry at [absoluteIndex], or null if it was evicted or never inserted. */
    fun getByAbsolute(absoluteIndex: Long): Entry? {
        val oldest = entries.firstOrNull() ?: return null
        if (absoluteIndex < oldest.absoluteIndex || absoluteIndex >= _insertCount) return null
        return entries[(absoluteIndex - oldest.absoluteIndex).toInt()]
    }

    /** True if [absoluteIndex] is currently live (not evicted, already inserted). */
    fun isLive(absoluteIndex: Long): Boolean = getByAbsolute(absoluteIndex) != null

    /** Absolute index of the lowest entry whose name AND value match, or null (newest-first preference). */
    fun findExact(
        name: String,
        value: String,
    ): Long? = entries.lastOrNull { it.name == name && it.value == value }?.absoluteIndex

    /** Absolute index of an entry whose name matches (any value), or null (newest-first preference). */
    fun findName(name: String): Long? = entries.lastOrNull { it.name == name }?.absoluteIndex
}
