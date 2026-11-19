package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.core.observe.Confined

/** One slot of a [LongObjectMap]'s table. */
private sealed interface Slot<out V> {
    data object Vacant : Slot<Nothing>

    class Filled<V>(
        val value: V,
    ) : Slot<V>
}

/**
 * A map from primitive longs to objects for one thread: open addressing with linear probing over a power-of-two table,
 * so a lookup allocates nothing and boxes no key; storing a new key allocates its slot, and growth its table. A
 * removal shifts the entries after it back, so no deleted marker lingers and lookups stay short. Its owner's thread
 * rule applies.
 */
@Confined
internal class LongObjectMap<V : Any> {
    private var keys = LongArray(MIN_CAPACITY)
    private var slots = vacantTable<V>(MIN_CAPACITY)
    private var count = 0

    val size: Int get() = count

    fun containsKey(key: Long): Boolean = find(key, { true }, { false })

    /** The value of [key], or [default] when it has none. */
    fun getOrDefault(
        key: Long,
        default: V,
    ): V = find(key, { it }, { default })

    /** The value of [key], made by [make] and stored when it has none; a [make] that captures nothing allocates nothing. */
    fun getOrPut(
        key: Long,
        make: (Long) -> V,
    ): V =
        find(key, { it }, {
            val made = make(key)
            set(key, made)
            made
        })

    operator fun set(
        key: Long,
        value: V,
    ) {
        var at = home(key)
        while (slots[at] is Slot.Filled) {
            if (keys[at] == key) {
                slots[at] = Slot.Filled(value)
                return
            }
            at = (at + 1) and mask()
        }
        keys[at] = key
        slots[at] = Slot.Filled(value)
        count++
        if (count * LOAD_DENOMINATOR > keys.size * LOAD_NUMERATOR) grow()
    }

    /** Removes [key]; whether it was there. */
    fun remove(key: Long): Boolean {
        var hole = home(key)
        while (true) {
            if (slots[hole] is Slot.Vacant) return false
            if (keys[hole] == key) break
            hole = (hole + 1) and mask()
        }
        count--
        // Backward shift: each following entry of the run moves into the hole unless its home lies after the hole.
        var at = (hole + 1) and mask()
        while (slots[at] is Slot.Filled) {
            if (((at - home(keys[at])) and mask()) >= ((at - hole) and mask())) {
                keys[hole] = keys[at]
                slots[hole] = slots[at]
                hole = at
            }
            at = (at + 1) and mask()
        }
        slots[hole] = Slot.Vacant
        return true
    }

    fun forEachValue(each: (V) -> Unit) {
        for (slot in slots) {
            when (slot) {
                is Slot.Filled -> each(slot.value)
                is Slot.Vacant -> Unit
            }
        }
    }

    private inline fun <R> find(
        key: Long,
        found: (V) -> R,
        missing: () -> R,
    ): R {
        var at = home(key)
        while (true) {
            when (val slot = slots[at]) {
                is Slot.Vacant -> return missing()
                is Slot.Filled -> if (keys[at] == key) return found(slot.value)
            }
            at = (at + 1) and mask()
        }
    }

    private fun mask(): Int = keys.size - 1

    private fun home(key: Long): Int = ((key * SPREAD) ushr (Long.SIZE_BITS - bitsOf(keys.size))).toInt()

    private fun grow() {
        val oldKeys = keys
        val oldSlots = slots
        keys = LongArray(oldKeys.size * 2)
        slots = vacantTable<V>(keys.size)
        count = 0
        for (i in oldKeys.indices) {
            when (val slot = oldSlots[i]) {
                is Slot.Filled -> set(oldKeys[i], slot.value)
                is Slot.Vacant -> Unit
            }
        }
    }

    private companion object {
        const val MIN_CAPACITY = 8
        const val LOAD_NUMERATOR = 3
        const val LOAD_DENOMINATOR = 4

        /** Fibonacci hashing: spreads keys that differ only in low bits, as stream ids do. */
        const val SPREAD = -0x61c8864680b583ebL

        fun <V> vacantTable(size: Int): MutableList<Slot<V>> = MutableList(size) { Slot.Vacant }

        fun bitsOf(size: Int): Int = Int.SIZE_BITS - 1 - size.countLeadingZeroBits()
    }
}
