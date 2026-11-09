@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.udp

import kotlinx.coroutines.DisposableHandle

/**
 * One deadline armed on a [DatagramEventLoop]: [action] runs on the loop's thread at [deadlineNanos]
 * (a `System.nanoTime()` reading) unless it is disposed first.
 */
internal class LoopTimer(
    val deadlineNanos: Long,
    val action: Runnable,
    private val owner: DatagramEventLoop,
) : DisposableHandle,
    Comparable<LoopTimer> {
    /** Where this timer sits in its heap, or -1 when it is in none. Loop thread only. */
    var heapIndex: Int = -1

    /** Arming order, so two timers due at the same instant fire in the order they were armed. Loop thread only. */
    var sequence: Long = 0

    /** Set by [dispose] on any thread; a disposed timer never runs, even one already polled. */
    @Volatile
    var disposed: Boolean = false

    override fun compareTo(other: LoopTimer): Int {
        val byDeadline =
            (deadlineNanos - other.deadlineNanos).let {
                if (it < 0) {
                    -1
                } else if (it > 0) {
                    1
                } else {
                    0
                }
            }
        return if (byDeadline != 0) byDeadline else sequence.compareTo(other.sequence)
    }

    /**
     * Takes the timer out of its heap at once when called on the loop, or through a task when called
     * from elsewhere, so a deadline disarmed long before it is due (the driver re-arms its wake on
     * every command) does not stay queued until then.
     */
    override fun dispose() = owner.disarm(this)
}

/**
 * A binary min-heap of [LoopTimer]s that can remove any member in O(log n), confined to one loop's
 * thread. Removal by index is the point: most deadlines are disarmed, not fired.
 */
internal class LoopTimerHeap {
    private var items = arrayOfNulls<LoopTimer>(16)

    var size: Int = 0
        private set

    fun peek(): LoopTimer? = if (size == 0) null else items[0]

    fun add(timer: LoopTimer) {
        if (timer.heapIndex >= 0) return
        if (size == items.size) items = items.copyOf(size * 2)
        items[size] = timer
        timer.heapIndex = size
        size++
        siftUp(timer.heapIndex)
    }

    /** Removes [timer] if it is in this heap. */
    fun remove(timer: LoopTimer) {
        val index = timer.heapIndex
        if (index < 0 || index >= size || items[index] !== timer) return
        val last = size - 1
        if (index != last) {
            val moved = items[last]!!
            items[index] = moved
            moved.heapIndex = index
        }
        items[last] = null
        size = last
        timer.heapIndex = -1
        if (index < size) {
            siftDown(index)
            siftUp(index)
        }
    }

    fun poll(): LoopTimer? = peek()?.also { remove(it) }

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val parent = (i - 1) / 2
            if (items[i]!! >= items[parent]!!) return
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val left = 2 * i + 1
            if (left >= size) return
            val right = left + 1
            val smallest = if (right < size && items[right]!! < items[left]!!) right else left
            if (items[i]!! <= items[smallest]!!) return
            swap(i, smallest)
            i = smallest
        }
    }

    private fun swap(
        a: Int,
        b: Int,
    ) {
        val x = items[a]!!
        val y = items[b]!!
        items[a] = y
        y.heapIndex = a
        items[b] = x
        x.heapIndex = b
    }
}
