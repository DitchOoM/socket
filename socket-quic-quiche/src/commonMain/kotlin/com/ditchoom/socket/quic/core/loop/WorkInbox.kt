@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A link in a [WorkInbox]: the next item, or the end. */
internal sealed interface InboxLink {
    object End : InboxLink

    /** The inbox was closed; nothing more is taken. */
    object Closed : InboxLink
}

/** An item that can sit in one [WorkInbox] at a time. The item is its own link, so pushing allocates nothing. */
internal abstract class InboxItem : InboxLink {
    internal var next: InboxLink = InboxLink.End
}

internal sealed interface Pushed {
    /** The inbox was empty: the consumer may be waiting and needs waking. */
    object First : Pushed

    object Behind : Pushed

    /** The inbox is closed: the item was not taken. */
    object Refused : Pushed
}

/**
 * Many producers, one consumer, no lock. Producers push onto a stack; the consumer takes the whole stack in one
 * exchange and runs it oldest first.
 */
internal class WorkInbox<T : InboxItem> {
    private val head = AtomicReference<InboxLink>(InboxLink.End)

    fun push(item: T): Pushed {
        while (true) {
            val seen = head.load()
            if (seen === InboxLink.Closed) return Pushed.Refused
            item.next = seen
            if (head.compareAndSet(seen, item)) return if (seen === InboxLink.End) Pushed.First else Pushed.Behind
        }
    }

    /** Consumer only. Runs every item pushed before this call, oldest first; returns how many. */
    fun drain(run: (T) -> Unit): Int = runAll(take(InboxLink.End), run)

    /** Consumer only, once. Refuses every later push and runs what was still queued, oldest first. */
    fun close(run: (T) -> Unit): Int = runAll(take(InboxLink.Closed), run)

    private fun take(replacement: InboxLink): InboxLink {
        while (true) {
            val seen = head.load()
            if (seen === InboxLink.Closed) return InboxLink.End
            if (head.compareAndSet(seen, replacement)) return seen
        }
    }

    private fun runAll(
        taken: InboxLink,
        run: (T) -> Unit,
    ): Int {
        var newestFirst = taken
        var oldestFirst: InboxLink = InboxLink.End
        while (newestFirst is InboxItem) {
            val following = newestFirst.next
            newestFirst.next = oldestFirst
            oldestFirst = newestFirst
            newestFirst = following
        }
        var count = 0
        while (oldestFirst is InboxItem) {
            val following = oldestFirst.next
            oldestFirst.next = InboxLink.End
            @Suppress("UNCHECKED_CAST")
            run(oldestFirst as T)
            count++
            oldestFirst = following
        }
        return count
    }

    fun looksEmpty(): Boolean = head.load() !is InboxItem
}
