package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.core.observe.Confined

/** One slot of a [LongObjectMap]'s table. */
private sealed interface MapSlot<out V> {
    data object Vacant : MapSlot<Nothing>

    class Filled<V>(
        var value: V,
    ) : MapSlot<V>
}

/**
 * A map from primitive longs to objects for one thread: open addressing with linear probing over a power-of-two table,
 * so a lookup allocates nothing and boxes no key. A removed key's slot is kept for the next new key, holding [cleared]
 * until then, so a map whose size stays under its peak allocates nothing; growth allocates its table. A removal
 * shifts the entries after it back, so no deleted marker lingers and lookups stay short. Its owner's thread rule
 * applies.
 */
@Confined
internal class LongObjectMap<V : Any>(
    private val cleared: V,
) {
    private var keys = LongArray(MIN_CAPACITY)
    private var slots = vacantTable<V>(MIN_CAPACITY)
    private val spareSlots = ArrayList<MapSlot.Filled<V>>()
    private var count = 0

    val size: Int get() = count

    fun containsKey(key: Long): Boolean = find(key, { true }, { false })

    /** The value of [key], or [default] when it has none. */
    fun getOrDefault(
        key: Long,
        default: V,
    ): V = find(key, { it }, { default })

    /** The slot holding [key], or [MapSlot.Vacant]. */
    private fun slotOf(key: Long): MapSlot<V> {
        var at = home(key)
        while (true) {
            when (val slot = slots[at]) {
                is MapSlot.Vacant -> return slot
                is MapSlot.Filled -> if (keys[at] == key) return slot
            }
            at = (at + 1) and mask()
        }
    }

    operator fun set(
        key: Long,
        value: V,
    ) {
        when (val slot = slotOf(key)) {
            is MapSlot.Filled -> slot.value = value
            is MapSlot.Vacant -> {
                val filled = if (spareSlots.isEmpty()) MapSlot.Filled(value) else spareSlots.removeAt(spareSlots.size - 1)
                filled.value = value
                place(key, filled)
                count++
                if (count * LOAD_DENOMINATOR > keys.size * LOAD_NUMERATOR) grow()
            }
        }
    }

    /** Removes [key]; whether it was there. */
    fun remove(key: Long): Boolean {
        var hole = home(key)
        while (true) {
            if (slots[hole] is MapSlot.Vacant) return false
            if (keys[hole] == key) break
            hole = (hole + 1) and mask()
        }
        when (val gone = slots[hole]) {
            is MapSlot.Filled -> {
                gone.value = cleared
                spareSlots.add(gone)
            }
            is MapSlot.Vacant -> Unit
        }
        count--
        // Backward shift: each following entry of the run moves into the hole unless its home lies after the hole.
        var at = (hole + 1) and mask()
        while (slots[at] is MapSlot.Filled) {
            if (((at - home(keys[at])) and mask()) >= ((at - hole) and mask())) {
                keys[hole] = keys[at]
                slots[hole] = slots[at]
                hole = at
            }
            at = (at + 1) and mask()
        }
        slots[hole] = MapSlot.Vacant
        return true
    }

    /** Removes every key, keeping the table's capacity. */
    fun clear() {
        slots.fill(MapSlot.Vacant)
        count = 0
    }

    fun forEachValue(each: (V) -> Unit) {
        for (slot in slots) {
            when (slot) {
                is MapSlot.Filled -> each(slot.value)
                is MapSlot.Vacant -> Unit
            }
        }
    }

    private inline fun <R> find(
        key: Long,
        found: (V) -> R,
        missing: () -> R,
    ): R =
        when (val slot = slotOf(key)) {
            is MapSlot.Filled -> found(slot.value)
            is MapSlot.Vacant -> missing()
        }

    /** Puts [filled] at the first vacant slot of [key]'s run; [key] is not in the table. */
    private fun place(
        key: Long,
        filled: MapSlot.Filled<V>,
    ) {
        var at = home(key)
        while (slots[at] is MapSlot.Filled) at = (at + 1) and mask()
        keys[at] = key
        slots[at] = filled
    }

    private fun mask(): Int = keys.size - 1

    private fun home(key: Long): Int = ((key * SPREAD) ushr (Long.SIZE_BITS - bitsOf(keys.size))).toInt()

    private fun grow() {
        val oldKeys = keys
        val oldSlots = slots
        keys = LongArray(oldKeys.size * 2)
        slots = vacantTable<V>(keys.size)
        for (i in oldKeys.indices) {
            when (val slot = oldSlots[i]) {
                is MapSlot.Filled -> place(oldKeys[i], slot)
                is MapSlot.Vacant -> Unit
            }
        }
    }

    private companion object {
        const val MIN_CAPACITY = 8
        const val LOAD_NUMERATOR = 3
        const val LOAD_DENOMINATOR = 4

        /** Fibonacci hashing: spreads keys that differ only in low bits, as stream ids do. */
        const val SPREAD = -0x61c8864680b583ebL

        fun <V> vacantTable(size: Int): MutableList<MapSlot<V>> = MutableList(size) { MapSlot.Vacant }

        fun bitsOf(size: Int): Int = Int.SIZE_BITS - 1 - size.countLeadingZeroBits()
    }
}
