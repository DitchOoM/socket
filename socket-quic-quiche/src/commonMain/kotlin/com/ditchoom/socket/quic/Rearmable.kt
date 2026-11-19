@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.core.observe.Link
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.Queue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmInline

/**
 * The time a [Rearmable] is armed for, or [None]. It is also the deadline's epoch: the loop fires only by moving
 * the very value it read to [None], so a re-arm that lands meanwhile makes that fire fail and is kept instead.
 */
@JvmInline
internal value class Wanted private constructor(
    private val nanos: Long,
) {
    inline fun <R> fold(
        none: () -> R,
        at: (nanos: Long) -> R,
    ): R = if (this == None) none() else at(atNanos())

    @PublishedApi
    internal fun atNanos(): Long = nanos

    internal fun word(): Long = nanos

    companion object {
        /** Not armed. No deadline is due at [Long.MAX_VALUE]. */
        val None = Wanted(Long.MAX_VALUE)

        fun at(nanos: Long): Wanted = Wanted(nanos)

        internal fun ofWord(word: Long): Wanted = Wanted(word)
    }
}

/** A rearmable's place in its loop's heap, or [Unqueued]. */
@JvmInline
internal value class HeapSlot private constructor(
    val index: Int,
) {
    companion object {
        val Unqueued = HeapSlot(-1)

        fun at(index: Int): HeapSlot = HeapSlot(index)
    }
}

/** A link in a [Rearmables] inbox: the next deadline posted, or the end. */
internal sealed interface PostLink {
    data object End : PostLink
}

/** What a [Rearmable] runs on its loop when an arming falls due; [due] is the time that arming asked for, its epoch. */
internal fun interface DeadlineFired {
    fun fired(due: Long)
}

/** Whether a [Rearmable] sits in its loop's inbox. */
private enum class Posted { No, Yes }

/**
 * A deadline made once and re-armed in place for its owner's whole life, so arming, moving and disarming it allocate
 * nothing. Its owner, on any thread, says what it [wanted]; the loop alone moves it into, within and out of the loop's
 * heap. Every change is posted to the loop at most once at a time, and the loop reads [wanted] after taking the post,
 * so the latest change is the one the loop sees. [action] runs on the loop when the wanted time falls due, once per arming.
 */
@Queue
internal class Rearmable internal constructor(
    private val loop: Rearmables,
    private val action: DeadlineFired,
) : PostLink {
    private val wanted = AtomicLong(Wanted.None.word())
    private val posted = AtomicReference(Posted.No)

    @LoopOnly
    internal var key: Long = 0L

    @LoopOnly
    internal var order: Long = 0L

    @LoopOnly
    internal var slot: HeapSlot = HeapSlot.Unqueued

    @Link
    internal var next: PostLink = PostLink.End

    /** Any thread: what the deadline is armed for now. */
    fun wanted(): Wanted = Wanted.ofWord(wanted.load())

    /** Any thread: arms for [dueNanos], in place of whatever was armed. */
    fun arm(dueNanos: Long) {
        wanted.store(dueNanos)
        post()
    }

    /** Any thread: disarms; the loop lets go of it at its next pass. */
    fun disarm() {
        wanted.store(Wanted.None.word())
        post()
    }

    private fun post() {
        if (posted.load() == Posted.No && posted.compareAndSet(Posted.No, Posted.Yes)) loop.post(this)
    }

    /** Loop thread: the post was taken; a change after this posts again. */
    internal fun taken() = posted.store(Posted.No)

    internal fun wantedWord(): Long = wanted.load()

    /** Loop thread: takes the arming for [seen] if it is still what is wanted; false when a change landed first. */
    internal fun claim(seen: Long): Boolean = wanted.compareAndSet(seen, Wanted.None.word())

    /** Loop thread, after [claim] of [seen]: runs the action. One that throws is reported and the loop goes on. */
    internal fun fire(seen: Long) = runGuarded { action.fired(seen) }
}

/**
 * The loop's [Rearmable] deadlines: an inbox any thread posts to, lock-free, and a heap the loop thread alone keeps,
 * ordered by each deadline's key, which is its wanted time when the loop last looked. A deadline moved later is left
 * where it is and moved when its old key comes up, without firing; one moved earlier or disarmed is posted, and the
 * loop moves it at its next pass.
 */
@Queue
internal class Rearmables {
    private val inbox = AtomicReference<PostLink>(PostLink.End)

    @LoopOnly
    private val heap = ArrayList<Rearmable>(INITIAL_CAPACITY)

    @LoopOnly
    private var orders = 0L

    /** Any thread: a deadline that runs [action], not armed. */
    fun make(action: DeadlineFired): Rearmable = Rearmable(this, action)

    internal fun post(deadline: Rearmable) {
        while (true) {
            val head = inbox.load()
            deadline.next = head
            if (inbox.compareAndSet(head, deadline)) return
        }
    }

    fun hasInbox(): Boolean = inbox.load() !== PostLink.End

    /** Loop thread: held deadlines, for a test. */
    val size: Int get() = heap.size

    /** Loop thread: fires every deadline wanted at or before [nowNanos]. */
    fun fireDue(nowNanos: Long) {
        drain()
        while (heap.isNotEmpty()) {
            val top = heap[0]
            if (top.key > nowNanos) return
            val seen = top.wantedWord()
            when {
                seen == Wanted.None.word() -> removeAt(0)
                seen > nowNanos -> rekey(top, seen)
                top.claim(seen) -> {
                    // Out of the heap before the action runs: an action that re-arms posts, and the next pass places it.
                    removeAt(0)
                    top.fire(seen)
                }
            }
        }
    }

    /** Loop thread: when the earliest armed deadline falls due. */
    fun nextDue(): NextDue {
        drain()
        while (heap.isNotEmpty()) {
            val top = heap[0]
            val seen = top.wantedWord()
            when (seen) {
                Wanted.None.word() -> removeAt(0)
                top.key -> return NextDue.at(seen)
                else -> rekey(top, seen)
            }
        }
        return NextDue.None
    }

    private fun drain() {
        var chain = inbox.exchange(PostLink.End)
        while (chain is Rearmable) {
            val following = chain.next
            chain.next = PostLink.End
            chain.taken()
            place(chain)
            chain = following
        }
    }

    /** Puts [deadline] where what it wants says: in the heap at its time, or out of it. */
    private fun place(deadline: Rearmable) {
        val seen = deadline.wantedWord()
        val slot = deadline.slot
        when {
            seen == Wanted.None.word() -> if (slot != HeapSlot.Unqueued) removeAt(slot.index)
            slot == HeapSlot.Unqueued -> {
                deadline.key = seen
                deadline.order = orders++
                deadline.slot = HeapSlot.at(heap.size)
                heap.add(deadline)
                siftUp(heap.size - 1)
            }
            else -> rekey(deadline, seen)
        }
    }

    private fun rekey(
        deadline: Rearmable,
        key: Long,
    ) {
        deadline.key = key
        deadline.order = orders++
        val at = deadline.slot.index
        siftUp(at)
        siftDown(deadline.slot.index)
    }

    private fun removeAt(index: Int) {
        val gone = heap[index]
        val last = heap.removeAt(heap.size - 1)
        gone.slot = HeapSlot.Unqueued
        if (index < heap.size) {
            heap[index] = last
            last.slot = HeapSlot.at(index)
            siftUp(index)
            siftDown(last.slot.index)
        }
    }

    private fun before(
        a: Rearmable,
        b: Rearmable,
    ): Boolean = a.key < b.key || (a.key == b.key && a.order < b.order)

    private fun siftUp(from: Int) {
        var i = from
        val node = heap[i]
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!before(node, heap[parent])) break
            put(i, heap[parent])
            i = parent
        }
        put(i, node)
    }

    private fun siftDown(from: Int) {
        var i = from
        val node = heap[i]
        while (true) {
            var child = 2 * i + 1
            if (child >= heap.size) break
            if (child + 1 < heap.size && before(heap[child + 1], heap[child])) child++
            if (!before(heap[child], node)) break
            put(i, heap[child])
            i = child
        }
        put(i, node)
    }

    private fun put(
        index: Int,
        deadline: Rearmable,
    ) {
        heap[index] = deadline
        deadline.slot = HeapSlot.at(index)
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
