@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a plain-form stream still has a direction its block could leave open. */
internal enum class PlainState { Open, Done }

/** The plain-form streams of one connection, newest first, or that its block ended. */
private sealed interface PlainList {
    data object Empty : PlainList

    class Both(
        val stream: CoreBidiStream,
        val next: PlainList,
    ) : PlainList

    class SendOnly(
        val stream: CoreSendStream,
        val next: PlainList,
    ) : PlainList

    data object BlockEnded : PlainList
}

/**
 * A connection's plain-form streams, so its block's end cuts those still open. Lock-free: an open pushes one node,
 * dropping streams that are done from the front as it goes; a done stream behind an open one stays listed until the
 * open one is done too.
 */
@Published
internal class PlainStreams {
    private val head = AtomicReference<PlainList>(PlainList.Empty)

    /** [stream] was opened in the plain form; one opened after the block ended is cut at once. */
    fun opened(stream: CoreBidiStream) = push(stream::cut) { rest -> PlainList.Both(stream, rest) }

    /** [stream] was opened in the plain form; one opened after the block ended is cut at once. */
    fun opened(stream: CoreSendStream) = push(stream::cut) { rest -> PlainList.SendOnly(stream, rest) }

    /** The block ended: every plain-form stream still open is cut, once. */
    fun blockEnded() {
        var at = head.exchange(PlainList.BlockEnded)
        while (true) {
            at =
                when (at) {
                    is PlainList.Both -> {
                        if (at.stream.plainState() == PlainState.Open) at.stream.cut()
                        at.next
                    }
                    is PlainList.SendOnly -> {
                        if (at.stream.plainState() == PlainState.Open) at.stream.cut()
                        at.next
                    }
                    is PlainList.Empty, is PlainList.BlockEnded -> return
                }
        }
    }

    private inline fun push(
        cut: () -> Unit,
        node: (PlainList) -> PlainList,
    ) {
        while (true) {
            val seen = head.load()
            val rest =
                when (seen) {
                    is PlainList.BlockEnded -> return cut()
                    is PlainList.Empty, is PlainList.Both, is PlainList.SendOnly -> pruned(seen)
                }
            if (head.compareAndSet(seen, node(rest))) return
        }
    }

    private fun pruned(list: PlainList): PlainList {
        var at = list
        while (true) {
            at =
                when (at) {
                    is PlainList.Both -> if (at.stream.plainState() == PlainState.Done) at.next else return at
                    is PlainList.SendOnly -> if (at.stream.plainState() == PlainState.Done) at.next else return at
                    is PlainList.Empty, is PlainList.BlockEnded -> return at
                }
        }
    }
}
