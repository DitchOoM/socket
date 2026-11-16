package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.core.observe.Confined

/** A set of primitive longs for one thread, over a [LongObjectMap]: no key is boxed. */
@Confined
internal class LongSet {
    private val map = LongObjectMap<Present>()

    fun add(key: Long) {
        map[key] = Present
    }

    /** Removes [key]; whether it was there. */
    fun remove(key: Long): Boolean = map.remove(key)

    fun isNotEmpty(): Boolean = map.size > 0

    private object Present
}

/** A first-in, first-out queue of primitive longs for one thread: a ring that grows, no element boxed. */
@Confined
internal class LongQueue {
    private var ring = LongArray(INITIAL)
    private var head = 0
    private var count = 0

    fun isNotEmpty(): Boolean = count > 0

    fun addLast(value: Long) {
        if (count == ring.size) grow()
        ring[(head + count) and (ring.size - 1)] = value
        count++
    }

    /** Takes every value out, oldest first, handing each to [each]; values [each] adds are taken too. */
    inline fun drain(each: (Long) -> Unit) {
        while (isNotEmpty()) each(takeOldest())
    }

    @PublishedApi
    internal fun takeOldest(): Long {
        val value = ring[head]
        head = (head + 1) and (ring.size - 1)
        count--
        return value
    }

    private fun grow() {
        val grown = LongArray(ring.size * 2)
        for (i in 0 until count) grown[i] = ring[(head + i) and (ring.size - 1)]
        ring = grown
        head = 0
    }

    private companion object {
        const val INITIAL = 8
    }
}

/** A last-in, first-out stack of primitive ints for one thread, no element boxed. */
@Confined
internal class IntStack {
    private var items = IntArray(INITIAL)
    private var count = 0

    fun push(value: Int) {
        if (count == items.size) items = items.copyOf(items.size * 2)
        items[count++] = value
    }

    /** The newest value, taken out, or [empty]'s answer when there is none. */
    inline fun popOr(empty: () -> Int): Int = if (held() == 0) empty() else takeNewest()

    @PublishedApi
    internal fun held(): Int = count

    @PublishedApi
    internal fun takeNewest(): Int = items[--count]

    private companion object {
        const val INITIAL = 8
    }
}
