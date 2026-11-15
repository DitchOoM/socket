package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/** A loop's wait on an NIO [Selector] over its one socket. */
internal class SelectorWait(
    channel: SelectableChannel,
    private val clock: LoopClock,
) : LoopWait {
    private val selector: Selector = Selector.open()
    private val key: SelectionKey = channel.register(selector, SelectionKey.OP_READ)

    override fun await(
        until: NextDue,
        write: WriteInterest,
    ): WakeCause {
        val wanted =
            when (write) {
                WriteInterest.None -> SelectionKey.OP_READ
                WriteInterest.Wanted -> SelectionKey.OP_READ or SelectionKey.OP_WRITE
            }
        if (key.interestOps() != wanted) key.interestOps(wanted)
        val ready =
            when (until) {
                NextDue.None -> selector.select()
                is NextDue.At -> {
                    val nanos = until.nanos - clock.nowNanos()
                    // The selector counts whole milliseconds; a shorter wait rounds up so a deadline is never early.
                    if (nanos <= 0) selector.selectNow() else selector.select((nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
                }
            }
        selector.selectedKeys().clear()
        return when {
            ready == 0 -> if (until is NextDue.At) WakeCause.Deadline else WakeCause.Woken
            key.isReadable -> WakeCause.Readable
            else -> WakeCause.Writable
        }
    }

    override fun wake() {
        selector.wakeup()
    }

    override fun close() {
        selector.close()
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
