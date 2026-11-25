@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: quiche's path events are read here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined

/** What quiche's path events said of one path since they were last read. */
internal enum class PathVerdict {
    Validated,

    /** Quiche gave up validating the path, or closed it. */
    Failed,

    /** Nothing that decides the path. */
    Undecided,

    /** The connection's memory has no address, so no event can be read. */
    Unreadable,
}

/**
 * A client connection's reader of quiche's path events: they are consumed in order, and those of other paths are
 * dropped. Its native memory is made the first time it reads and freed with [free]. Inside the connection only.
 */
@Confined
internal class PathEvents(
    private val q: QuicheConnRef,
    private val factory: BufferFactory,
) {
    private var memory: EventMemory = EventMemory.None

    /** Reads every event quiche holds and answers the first that decides the path from [local]. */
    fun verdict(local: PathKey): PathVerdict {
        val scratch =
            when (val held = memory) {
                is EventMemory.Held -> held.scratch
                is EventMemory.None ->
                    when (val taken = EventScratch.of(factory)) {
                        is EventsTaken.Taken -> taken.scratch.also { memory = EventMemory.Held(it) }
                        is EventsTaken.NotNative -> return PathVerdict.Unreadable
                    }
            }
        while (true) {
            val type =
                when (val news = scratch.next(q)) {
                    is EventNext.Event -> news.type
                    is EventNext.Drained -> return PathVerdict.Undecided
                }
            if (!q.addressIs(scratch.local, local)) continue
            if (type == QuichePathEventType.Validated) return PathVerdict.Validated
            if (type == QuichePathEventType.FailedValidation || type == QuichePathEventType.Closed) return PathVerdict.Failed
        }
    }

    fun free() {
        when (val held = memory) {
            is EventMemory.Held -> held.scratch.free()
            is EventMemory.None -> Unit
        }
    }
}
