package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.checkedAdd
import com.ditchoom.socket.quic.checkedModify
import com.ditchoom.socket.quic.checkedWait
import com.ditchoom.socket.quic.checkedWake
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.time.LoopClock

/** What opening a [NativePoller] did. */
internal sealed interface PollerOpened {
    class Opened(
        val poller: NativePoller,
    ) : PollerOpened

    class Refused(
        val refusal: SocketRefusal,
    ) : PollerOpened
}

/**
 * The [NetStack.Native] poller: kqueue or epoll in `qnet`, over the loop's own descriptors. A token is the loop's
 * index of the socket; the wait writes ready tokens into native memory the poller owns and reads them back as ints, so
 * nothing is boxed or allocated per wait, whatever the descriptors' numbers. Waits are level-triggered, as a selector's.
 */
@LoopOnly
internal class NativePoller private constructor(
    private val shim: NetShim,
    private val clock: LoopClock,
    private val handle: Long,
) : Poller {
    private var fds = IntArray(INITIAL_SOCKETS)
    private var watched = IntArray(INITIAL_SOCKETS)

    override fun add(
        socket: SocketRef,
        token: Int,
    ) {
        when (val handle = socket.handle) {
            is SocketHandle.Descriptor -> {
                grow(token)
                val fd = handle.current.asInt
                fds[token] = fd
                watched[token] = WATCHED
                shim.checkedAdd(this.handle, fd, token)
            }
            // A channel belongs to the JDK stack's selector; no stack builds one for this poller.
            is SocketHandle.Channel -> Unit
        }
    }

    override fun remove(token: Int) {
        if (token >= watched.size || watched[token] != WATCHED) return
        watched[token] = UNWATCHED
        shim.pollerRemove(handle, fds[token])
    }

    override fun interest(
        token: Int,
        write: WriteInterest,
    ) {
        if (token >= watched.size || watched[token] != WATCHED) return
        val writable =
            when (write) {
                WriteInterest.None -> 0
                WriteInterest.Wanted -> 1
            }
        shim.checkedModify(handle, fds[token], token, writable)
    }

    override fun await(
        until: NextDue,
        ready: ReadyTokens,
    ): WakeCause {
        ready.clear()
        val result =
            until.fold(
                none = { shim.checkedWait(handle, WAIT_FOREVER, 0L) },
                due = { due ->
                    val nanos = due - clock.now().value
                    if (nanos <= 0) shim.checkedWait(handle, WAIT_NOW, 0L) else shim.checkedWait(handle, WAIT_TIMED, nanos)
                },
            )
        var bits = 0
        for (i in 0 until (result ushr 1)) {
            val entry = shim.pollerReadyAt(handle, i)
            bits = bits or (entry and READY_BITS)
            ready.add(entry ushr 2)
        }
        return when {
            bits and READABLE != 0 -> WakeCause.Readable
            bits and WRITABLE != 0 -> WakeCause.Writable
            result and WOKEN != 0 -> WakeCause.Woken
            else -> WakeCause.Deadline
        }
    }

    override fun wake() = shim.checkedWake(handle)

    override fun close() = shim.pollerClose(handle)

    private fun grow(token: Int) {
        if (token < fds.size) return
        var size = fds.size
        while (size <= token) size *= 2
        fds = fds.copyOf(size)
        watched = watched.copyOf(size)
    }

    companion object {
        fun open(
            shim: NetShim,
            clock: LoopClock,
            capacity: Int,
        ): PollerOpened {
            val handle = shim.pollerOpen(capacity)
            return if (handle < 0) {
                PollerOpened.Refused(SocketRefusal.of((-handle).toInt()))
            } else {
                PollerOpened.Opened(NativePoller(shim, clock, handle))
            }
        }

        private const val INITIAL_SOCKETS = 8
        private const val UNWATCHED = 0
        private const val WATCHED = 1
        private const val WAIT_FOREVER = 0
        private const val WAIT_TIMED = 1
        private const val WAIT_NOW = 2
        private const val READABLE = 1
        private const val WRITABLE = 2
        private const val READY_BITS = 3
        private const val WOKEN = 1
    }
}
