@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StreamRecvResult
import com.ditchoom.socket.quic.StreamSendResult
import com.ditchoom.socket.quic.core.loop.Collected
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Entry
import com.ditchoom.socket.quic.core.loop.InboxItem
import com.ditchoom.socket.quic.core.loop.Leave
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopWork
import com.ditchoom.socket.quic.core.loop.OwnerWord
import com.ditchoom.socket.quic.core.loop.WorkInbox
import com.ditchoom.socket.quic.core.loop.WorkLeft
import com.ditchoom.socket.quic.core.udp.ReceivedDatagram
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The one path a connection has until migration arrives: what quiche is told on receive, and where output goes. */
internal class ConnPath(
    val recvInfo: QuicheRecvInfo,
    val sendInfo: QuicheSendInfo,
    val target: SendTarget,
)

internal sealed interface ConnLifecycle {
    object Handshaking : ConnLifecycle

    object Established : ConnLifecycle

    class Closed(
        val end: ConnEnd,
    ) : ConnLifecycle
}

internal sealed interface ConnEnd {
    object TimedOut : ConnEnd

    class ByPeer(
        val error: QuicError,
    ) : ConnEnd

    class Locally(
        val error: QuicError,
    ) : ConnEnd

    /** Closed with no error recorded on either side. */
    object Quietly : ConnEnd
}

/** What a connection tells whoever drives its streams. Called inside the connection, on the thread inside it. */
internal interface ConnEvents {
    fun established(inside: Conn.Inside)

    /** Level-triggered: told on every pass in which [stream] holds unread data or its end. */
    fun readable(
        inside: Conn.Inside,
        stream: QuicStreamId,
    )

    /** Told once per [Conn.Inside.wantWritable], when [stream] can take bytes again. */
    fun writable(
        inside: Conn.Inside,
        stream: QuicStreamId,
    )

    fun closed(end: ConnEnd)
}

/** Work for one connection, run inside it by whoever holds it. */
internal abstract class ConnWork : InboxItem() {
    abstract fun run(inside: Conn.Inside)
}

internal sealed interface WriteEnd {
    object More : WriteEnd

    object Finish : WriteEnd
}

/** Whether the loop is inside this connection in the current pass. Loop thread only. */
private sealed interface LoopHold {
    object Out : LoopHold

    object In : LoopHold
}

/**
 * One QUIC connection on one loop. The loop enters it when a datagram, a deadline or queued work touches it, serves
 * it, collects its output and leaves it in the same pass. Any other thread hands it work through [submit].
 */
internal class Conn(
    private val api: QuicheApi,
    private val handle: QuicheConn,
    private val path: ConnPath,
    private val loop: Loop,
    private val loopEntrant: Entrant,
    private val clock: LoopClock,
    private val events: ConnEvents,
) {
    private val owner = OwnerWord()
    private val inbox = WorkInbox<ConnWork>()
    private val inside = Inside()
    private val serve = ServeConn(this)
    private val serveQueued = AtomicInt(IDLE)
    private var hold: LoopHold = LoopHold.Out
    private var timer: TimerState = TimerState.Unarmed
    private val writeWaiters = HashSet<Long>()
    internal var lifecycle: ConnLifecycle = ConnLifecycle.Handshaking
        private set

    /**
     * The only way to reach this connection's quiche handle. One exists per connection and is handed to code
     * that is inside it, and to nothing else.
     */
    inner class Inside internal constructor() {
        val lifecycle: ConnLifecycle get() = this@Conn.lifecycle

        fun streamSend(
            stream: QuicStreamId,
            address: Long,
            length: Int,
            end: WriteEnd,
        ): StreamSendResult = api.connStreamSend(handle, stream, address, length, end === WriteEnd.Finish)

        fun streamRecv(
            stream: QuicStreamId,
            address: Long,
            length: Int,
        ): StreamRecvResult = api.connStreamRecv(handle, stream, address, length)

        /** Asks to be told [ConnEvents.writable] for [stream] once it can take bytes. */
        fun wantWritable(stream: QuicStreamId) {
            writeWaiters.add(stream.id)
        }

        fun close(error: QuicError) {
            api.connClose(handle, error)
        }
    }

    /** Any thread. Puts the connection in the loop's next pass: a new client's first flight goes out there. */
    fun start() = scheduleServe()

    /** Any thread. [work] runs inside the connection, on the loop or on whichever thread holds it then. */
    fun submit(work: ConnWork) {
        inbox.push(work)
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> Unit
            WorkLeft.NobodyInside -> scheduleServe()
        }
    }

    /** Loop thread: a datagram for this connection. */
    fun received(datagram: ReceivedDatagram) {
        when (touch()) {
            LoopHold.In -> api.connRecv(handle, datagram.address, datagram.length, path.recvInfo)
            LoopHold.Out -> Unit
        }
    }

    /**
     * Loop thread, once or more per pass after input: run queued work, tell [events] what changed, write output
     * into [out]. Leaves the connection when its output is all collected.
     */
    fun collect(out: SendBatch): Collected {
        if (hold === LoopHold.Out) return Collected.Everything
        inbox.drain { it.run(inside) }
        serveEvents()
        if (writeOutput(out) === Collected.MoreWaiting) return Collected.MoreWaiting
        rearm()
        return when (owner.tryLeave(loopEntrant)) {
            Leave.Left -> {
                hold = LoopHold.Out
                Collected.Everything
            }
            // Work arrived while the loop was inside: collect again in this pass.
            Leave.WorkArrived -> Collected.MoreWaiting
        }
    }

    internal fun touchedByLoop() {
        serveQueued.store(IDLE)
        touch()
    }

    private fun touch(): LoopHold {
        if (hold === LoopHold.In) return hold
        hold =
            when (owner.tryEnter(loopEntrant)) {
                Entry.Entered -> LoopHold.In
                // Only the loop enters until callers do (the edge); a datagram that finds a caller inside is then
                // copied into the connection's work, never dropped.
                Entry.Busy -> LoopHold.Out
            }
        return hold
    }

    private fun scheduleServe() {
        if (serveQueued.compareAndSet(IDLE, QUEUED)) loop.submit(serve)
    }

    private fun serveEvents() {
        when (lifecycle) {
            ConnLifecycle.Handshaking ->
                if (api.connIsEstablished(handle)) {
                    lifecycle = ConnLifecycle.Established
                    events.established(inside)
                }
            ConnLifecycle.Established, is ConnLifecycle.Closed -> Unit
        }
        if (lifecycle === ConnLifecycle.Established) {
            val readable = api.connReadable(handle)
            try {
                while (true) {
                    val id = api.streamIterNext(readable) ?: break
                    events.readable(inside, id)
                }
            } finally {
                api.streamIterFree(readable)
            }
            if (writeWaiters.isNotEmpty()) tellWritable()
        }
        if (lifecycle !is ConnLifecycle.Closed && api.connIsClosed(handle)) {
            val end = endOf()
            lifecycle = ConnLifecycle.Closed(end)
            disarm()
            events.closed(end)
        }
    }

    private fun tellWritable() {
        val writable = api.connWritable(handle)
        try {
            while (true) {
                val id = api.streamIterNext(writable) ?: break
                if (writeWaiters.remove(id.id)) events.writable(inside, id)
            }
        } finally {
            api.streamIterFree(writable)
        }
    }

    private fun writeOutput(out: SendBatch): Collected {
        while (out.room() === Room.Open) {
            val offset = out.nextOffset()
            val written = api.connSend(handle, out.address + offset, out.limits.maxDatagramSize, path.sendInfo)
            if (written <= 0) return Collected.Everything
            out.commit(written, path.target)
        }
        return Collected.MoreWaiting
    }

    private fun rearm() {
        disarm()
        val wait = api.connTimeout(handle) ?: return
        val node = loop.deadlines.arm(clock.nowNanos() + wait.inWholeNanoseconds, Runnable { timerFired() })
        timer = TimerState.Armed(node)
    }

    private fun disarm() {
        when (val armed = timer) {
            TimerState.Unarmed -> Unit
            is TimerState.Armed -> armed.node.disarm()
        }
        timer = TimerState.Unarmed
    }

    private fun timerFired() {
        timer = TimerState.Unarmed
        when (touch()) {
            LoopHold.In -> api.connOnTimeout(handle)
            // Whoever is inside leaves; the loop's next touch rearms from quiche's current timeout.
            LoopHold.Out -> scheduleServe()
        }
    }

    private fun endOf(): ConnEnd {
        if (api.connIsTimedOut(handle)) return ConnEnd.TimedOut
        api.connPeerError(handle)?.let { return ConnEnd.ByPeer(it) }
        api.connLocalError(handle)?.let { return ConnEnd.Locally(it) }
        return ConnEnd.Quietly
    }
}

private sealed interface TimerState {
    object Unarmed : TimerState

    class Armed(
        val node: DeadlineNode,
    ) : TimerState
}

/** Puts a connection in the loop's next pass. One per connection, queued at most once at a time. */
private class ServeConn(
    private val conn: Conn,
) : LoopWork() {
    override fun run(loop: Loop) = conn.touchedByLoop()
}

private const val IDLE = 0
private const val QUEUED = 1
