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

    // The consumer form of select: the JDK's selected-key set would allocate a node per ready event.
    private var readyOps = 0
    private val onReady = java.util.function.Consumer<SelectionKey> { readyOps = readyOps or it.readyOps() }

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
        readyOps = 0
        when (until) {
            NextDue.None -> selector.select(onReady)
            is NextDue.At -> {
                val nanos = until.nanos - clock.nowNanos()
                // The selector counts whole milliseconds; a shorter wait rounds up so a deadline is never early.
                if (nanos <= 0) selector.selectNow(onReady) else selector.select(onReady, (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
            }
        }
        return when {
            readyOps and SelectionKey.OP_READ != 0 -> WakeCause.Readable
            readyOps and SelectionKey.OP_WRITE != 0 -> WakeCause.Writable
            until is NextDue.At -> WakeCause.Deadline
            else -> WakeCause.Woken
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
