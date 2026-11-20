@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.core.observe.Pool
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A fixed set of items handed out and given back from any thread, without a lock and without allocating.
 *
 * The free items form a stack of indices. The head word holds the top index and a stamp that every change
 * increments, so a taker that read a head another thread has since changed and changed back fails its
 * compare-and-set instead of taking an item that was taken in between.
 */
@Pool
internal class FixedPool<T : Pooled>(
    count: Int,
    make: (index: Int) -> T,
) {
    @PublishedApi
    internal val items: List<T> = List(count) { index -> make(index) }

    // Indices are stored plus one, so zero is the empty stack and needs no sentinel.
    private val below = IntArray(count) { index -> index }
    private val head = AtomicLong(pack(stamp = 0L, top = count))

    val size: Int get() = items.size

    /** The item at [index], taken or not: for owners that keep a companion object per item. */
    operator fun get(index: Int): T = items[index]

    fun tryTake(): Taken = take({ it }, { Taken.Empty })

    /** Takes an item and answers [item] with it, or answers [empty] when the pool has none. */
    inline fun <R> take(
        item: (T) -> R,
        empty: () -> R,
    ): R {
        val top = takeTop()
        return if (top == 0) empty() else item(items[top - 1])
    }

    /** The taken item's index plus one, or zero when the pool is empty. */
    @PublishedApi
    internal fun takeTop(): Int {
        while (true) {
            val seen = head.load()
            val top = topOf(seen)
            if (top == EMPTY) return EMPTY
            if (head.compareAndSet(seen, pack(stampOf(seen) + 1, below[top - 1]))) return top
        }
    }

    /** Gives back an item taken from this pool. */
    fun give(item: T) {
        while (true) {
            val seen = head.load()
            below[item.index] = topOf(seen)
            if (head.compareAndSet(seen, pack(stampOf(seen) + 1, item.index + 1))) return
        }
    }

    /** Takes and gives that succeeded so far, modulo 2^32: each one stamps the head. Tests count a call's pool operations with it. */
    fun operations(): Long = stampOf(head.load())

    /** Items in the pool now. Diagnostics and tests: it can be stale by the time it returns. */
    fun available(): Int {
        var count = 0
        var at = topOf(head.load())
        while (at != EMPTY && count <= items.size) {
            count++
            at = below[at - 1]
        }
        return count
    }
}

private const val EMPTY = 0
private const val TOP_MASK = 0xFFFF_FFFFL

private fun pack(
    stamp: Long,
    top: Int,
): Long = (stamp shl 32) or (top.toLong() and TOP_MASK)

private fun topOf(word: Long): Int = (word and TOP_MASK).toInt()

private fun stampOf(word: Long): Long = word ushr 32
