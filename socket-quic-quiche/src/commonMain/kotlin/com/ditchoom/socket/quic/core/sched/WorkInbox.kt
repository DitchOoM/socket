@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Link
import com.ditchoom.socket.quic.core.observe.Queue
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A link in a [WorkInbox]: the next item, the end of the chain, or the marker of a shut inbox. */
internal sealed interface InboxLink<out T> {
    data object End : InboxLink<Nothing>
}

/** What [WorkInbox.push] answers: the inbox took the item, or it was shut and says how its connection ended. */
internal sealed interface Pushed {
    data object Open : Pushed
}

/** The marker of a shut inbox. The submitter of refused work reads the end from the same word it was refused by. */
internal class Shut(
    val end: ConnEnd,
) : InboxLink<Nothing>,
    Pushed

/** An item that can sit in one [WorkInbox] at a time. The item is its own link, so pushing allocates nothing. */
@Queue
internal abstract class InboxItem<T : InboxItem<T>> : InboxLink<T> {
    @Link
    internal var next: InboxLink<T> = InboxLink.End

    /** This item as its own type, so the inbox runs it without a cast. */
    abstract fun self(): T
}

/**
 * Many producers, no lock. Producers push onto a stack; a consumer takes the whole stack in one exchange and runs it
 * oldest first. Once shut, a push is refused with the end the inbox was shut with.
 */
@Queue
internal class WorkInbox<T : InboxItem<T>> {
    private val head = AtomicReference<InboxLink<T>>(InboxLink.End)

    fun push(item: T): Pushed {
        while (true) {
            val seen = head.load()
            if (seen is Shut) return seen
            item.next = seen
            if (head.compareAndSet(seen, item)) return Pushed.Open
        }
    }

    /**
     * Runs every item pushed before this call, oldest first; returns how many. Several threads may drain at once: each
     * takes the whole chain it saw with one compare-and-set, so the chains they run are disjoint and no item runs twice.
     */
    fun drain(run: (T) -> Unit): Int = runAll(take(InboxLink.End), run)

    /** Consumer only, once. Refuses every later push with [end] and runs what was still queued, oldest first. */
    fun shut(
        end: ConnEnd,
        run: (T) -> Unit,
    ): Int = runAll(take(Shut(end)), run)

    private fun take(replacement: InboxLink<T>): InboxLink<T> {
        while (true) {
            val seen = head.load()
            if (seen is Shut) return InboxLink.End
            if (head.compareAndSet(seen, replacement)) return seen
        }
    }

    private fun runAll(
        taken: InboxLink<T>,
        run: (T) -> Unit,
    ): Int {
        var newestFirst = taken
        var oldestFirst: InboxLink<T> = InboxLink.End
        while (newestFirst is InboxItem<T>) {
            val following = newestFirst.next
            newestFirst.next = oldestFirst
            oldestFirst = newestFirst
            newestFirst = following
        }
        var count = 0
        while (oldestFirst is InboxItem<T>) {
            val following = oldestFirst.next
            oldestFirst.next = InboxLink.End
            run(oldestFirst.self())
            count++
            oldestFirst = following
        }
        return count
    }

    fun looksEmpty(): Boolean = head.load() !is InboxItem<T>
}
