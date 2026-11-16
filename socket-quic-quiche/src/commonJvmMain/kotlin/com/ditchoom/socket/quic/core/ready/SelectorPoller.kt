package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.time.LoopClock
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.function.Consumer

private sealed interface Scanner {
    class ByConsumer(
        val scan: ConsumerScan,
    ) : Scanner

    class BySet(
        val scan: SetScan,
    ) : Scanner
}

/**
 * The [NetStack.Jdk] poller: an NIO [Selector] over the loop's sockets. A socket's token is its place in
 * registration order, found by identity in a short array, so no map from key to token allocates per ready event.
 */
@LoopOnly
internal class SelectorPoller(
    private val clock: LoopClock,
    scan: SelectedKeys,
) : Poller {
    private val selector: Selector = Selector.open()
    private val keys = ArrayList<SelectionKey>()
    private var tokens = IntArray(INITIAL_SOCKETS)
    private var readyOps = 0
    private var current = ReadyTokens(0)
    private val onReady = Consumer<SelectionKey> { key -> found(key) }
    private val scanner: Scanner =
        when (scan) {
            SelectedKeys.ByConsumer -> Scanner.ByConsumer(ConsumerScan(selector, onReady))
            SelectedKeys.BySet -> Scanner.BySet(SetScan(selector, onReady))
        }

    override fun add(
        socket: SocketRef,
        token: Int,
    ) {
        if (keys.size == tokens.size) tokens = tokens.copyOf(tokens.size * 2)
        tokens[keys.size] = token
        when (val handle = socket.handle) {
            is SocketHandle.Channel -> keys.add(handle.current.get().register(selector, SelectionKey.OP_READ))
            // A descriptor belongs to the native stack's poller; no stack builds one for this selector.
            is SocketHandle.Descriptor -> Unit
        }
    }

    override fun remove(token: Int) {
        for (i in keys.indices) {
            if (tokens[i] != token) continue
            keys[i].cancel()
            keys.removeAt(i)
            for (j in i until keys.size) tokens[j] = tokens[j + 1]
            // The JDK closes a registered channel's descriptor only once its key is deregistered, in a select: do it
            // now, so a channel closed after this frees its port at once and a socket reopened on it can bind it.
            select(Wait.Now, 0L)
            return
        }
    }

    override fun interest(
        token: Int,
        write: WriteInterest,
    ) {
        val wanted =
            when (write) {
                WriteInterest.None -> SelectionKey.OP_READ
                WriteInterest.Wanted -> SelectionKey.OP_READ or SelectionKey.OP_WRITE
            }
        for (i in keys.indices) {
            if (tokens[i] == token && keys[i].interestOps() != wanted) keys[i].interestOps(wanted)
        }
    }

    override fun await(
        until: NextDue,
        ready: ReadyTokens,
    ): WakeCause {
        ready.clear()
        current = ready
        readyOps = 0
        until.fold(
            none = { select(Wait.Forever, 0L) },
            due = { due ->
                val nanos = due - clock.now().value
                // The selector counts whole milliseconds; a shorter wait rounds up so a deadline is never early.
                if (nanos <= 0) {
                    select(Wait.Now, 0L)
                } else {
                    select(Wait.Timed, (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
                }
            },
        )
        return when {
            readyOps and SelectionKey.OP_READ != 0 -> WakeCause.Readable
            readyOps and SelectionKey.OP_WRITE != 0 -> WakeCause.Writable
            until != NextDue.None -> WakeCause.Deadline
            else -> WakeCause.Woken
        }
    }

    override fun wake() {
        selector.wakeup()
    }

    override fun close() {
        selector.close()
    }

    private fun found(key: SelectionKey) {
        readyOps = readyOps or key.readyOps()
        for (i in keys.indices) {
            if (keys[i] === key) {
                current.add(tokens[i])
                return
            }
        }
    }

    private fun select(
        how: Wait,
        millis: Long,
    ): Int =
        when (val s = scanner) {
            is Scanner.ByConsumer ->
                when (how) {
                    Wait.Forever -> s.scan.forever()
                    Wait.Timed -> s.scan.timed(millis)
                    Wait.Now -> s.scan.now()
                }
            is Scanner.BySet ->
                when (how) {
                    Wait.Forever -> s.scan.forever()
                    Wait.Timed -> s.scan.timed(millis)
                    Wait.Now -> s.scan.now()
                }
        }
}

private enum class Wait { Forever, Timed, Now }

private const val NANOS_PER_MILLI = 1_000_000L
private const val INITIAL_SOCKETS = 4
