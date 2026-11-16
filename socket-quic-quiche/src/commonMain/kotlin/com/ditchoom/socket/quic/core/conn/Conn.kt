@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import androidx.collection.MutableLongSet
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.DeadlineQueue
import com.ditchoom.socket.quic.NO_TIMEOUT_NANOS
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StreamRecvStep
import com.ditchoom.socket.quic.StreamSendResult
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.loop.CallerSend
import com.ditchoom.socket.quic.core.loop.Collected
import com.ditchoom.socket.quic.core.loop.ConnHost
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Entry
import com.ditchoom.socket.quic.core.loop.InboxItem
import com.ditchoom.socket.quic.core.loop.LaneSent
import com.ditchoom.socket.quic.core.loop.Leave
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopHost
import com.ditchoom.socket.quic.core.loop.LoopWork
import com.ditchoom.socket.quic.core.loop.OwnerWord
import com.ditchoom.socket.quic.core.loop.Pushed
import com.ditchoom.socket.quic.core.loop.WorkInbox
import com.ditchoom.socket.quic.core.loop.WorkLeft
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.reportOnThisThread
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
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

    /** Processing this connection threw [cause]; it was closed on the wire with INTERNAL_ERROR where quiche allowed. */
    class Failed(
        val cause: Exception,
    ) : ConnEnd

    /** The loop thread failed; quiche's connection was not closed on the wire. */
    class LoopFailed(
        val cause: Throwable,
    ) : ConnEnd

    /** An `Error` was thrown while a caller's thread was inside; quiche's connection was not closed on the wire. */
    class CallerFailed(
        val cause: Throwable,
    ) : ConnEnd

    /** Its server was closed: the connection's CONNECTION_CLOSE went out before the server's loop stopped. */
    object ServerClosed : ConnEnd
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

    /** Once per pass of an established connection, after [readable] and [writable]. */
    fun served(inside: Conn.Inside)
}

/** Work for one connection, run inside it by whoever holds it. */
internal abstract class ConnWork : InboxItem() {
    abstract fun run(inside: Conn.Inside)

    /** The connection was released before the work could run. Called once, instead of [run]. */
    abstract fun gone()
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

/** Told once, by the thread that frees the connection, when nothing can reach the memory callers used. */
internal fun interface ConnFreed {
    fun freed()
}

/** Whether the connection's quiche handle has been freed, or is to be freed by whoever is inside last. */
private sealed interface Release {
    object Live : Release

    class Requested(
        val then: ConnFreed,
    ) : Release

    object Freed : Release
}

/** What a connection did with the threads and datagrams that reached it. Atomic: any thread counts, tests read. */
internal class ConnCounts {
    private val entered = AtomicLong(0L)
    private val busy = AtomicLong(0L)
    private val handed = AtomicLong(0L)
    private val copied = AtomicLong(0L)
    private val dropped = AtomicLong(0L)

    /** Times a caller entered a free connection and served it on its own thread. */
    val enteredInPlace: Long get() = entered.load()

    /** Times a caller left work for the thread already inside. */
    val leftForHolder: Long get() = busy.load()

    /** Times work reached a free connection and the loop was asked to serve it. */
    val handedToLoop: Long get() = handed.load()

    /** Datagrams that arrived while a caller was inside and were copied into the connection's work. */
    val datagramsCopied: Long get() = copied.load()

    /** Datagrams that arrived after the connection stopped taking work. */
    val datagramsDropped: Long get() = dropped.load()

    internal fun enteredInPlace() {
        entered.fetchAndAdd(1L)
    }

    internal fun leftForHolder() {
        busy.fetchAndAdd(1L)
    }

    internal fun handedToLoop() {
        handed.fetchAndAdd(1L)
    }

    internal fun datagramCopied() {
        copied.fetchAndAdd(1L)
    }

    internal fun datagramDropped() {
        dropped.fetchAndAdd(1L)
    }
}

/** A receive-sized buffer a datagram is copied into when it arrives while a caller is inside. */
internal class DatagramSlot(
    index: Int,
    val buffer: PlatformBuffer,
    /** The native address of [buffer]'s first byte. */
    val address: Long,
) : Pooled(index)

/** What a connection copies datagrams into when they arrive while a caller is inside: slots, or nothing. */
internal sealed interface DatagramSlots {
    object None : DatagramSlots

    class Of(
        val pool: FixedPool<DatagramSlot>,
    ) : DatagramSlots
}

/**
 * One QUIC connection on one loop. Whoever holds its ownership word is inside it and the only one touching its
 * quiche handle: the loop, when a datagram, a deadline or queued work touches it, or a caller that is allowed to
 * enter ([CallerPlace.MayEnter]) and finds it free. Whoever is inside serves it, collects its output and leaves.
 * Any thread hands it work through [submit].
 */
internal class Conn(
    private val api: QuicheApi,
    private val handle: QuicheConn,
    private val path: ConnPath,
    private val loop: ConnHost,
    private val loopEntrant: Entrant,
    private val clock: LoopClock,
    private val events: ConnEvents,
    /** The caller's factory: a datagram that finds no free slot is copied into a buffer from it. */
    private val factory: BufferFactory,
    /** Where datagrams that arrive while a caller is inside are copied to. */
    private val slots: DatagramSlots,
) {
    private val owner = OwnerWord()
    private val inbox = WorkInbox<ConnWork>()
    private val inside = Inside()
    private val runWork: (ConnWork) -> Unit = { it.run(inside) }
    private val onTimer = Runnable { timerFired() }
    private val serve = ServeConn(this)
    private val serveQueued = AtomicInt(IDLE)
    private val timerWork = TimerWork()
    private val timerQueued = AtomicInt(IDLE)
    private val callerEntrant = Entrant.caller(CALLER_TOKEN)
    private val releasing = AtomicReference<Release>(Release.Live)
    private val slotWork: Array<SlotRecv> =
        when (slots) {
            DatagramSlots.None -> emptyArray()
            is DatagramSlots.Of -> Array(slots.pool.size) { SlotRecv(slots.pool, slots.pool[it]) }
        }
    private var hold: LoopHold = LoopHold.Out
    private var timer: TimerState = TimerState.Unarmed
    private var sendHold: SendHold = SendHold.None
    private val writeWaiters = MutableLongSet()
    val counts = ConnCounts()
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
        ): StreamRecvStep = api.connStreamRecvStep(handle, stream, address, length)

        /** Asks to be told [ConnEvents.writable] for [stream] once it can take bytes. */
        fun wantWritable(stream: QuicStreamId) {
            writeWaiters.add(stream.id)
        }

        fun close(error: QuicError) {
            api.connClose(handle, error)
        }

        /** [direction] 0 stops the peer's sending (STOP_SENDING), 1 resets ours (RESET_STREAM). */
        fun shutdown(
            stream: QuicStreamId,
            direction: Int,
            code: Long,
        ): Int = api.connStreamShutdown(handle, stream, direction, code)

        /** Bidirectional streams the peer currently lets us open. */
        fun peerStreamsLeftBidi(): Long = api.connPeerStreamsLeftBidi(handle)

        /** Copies the negotiated ALPN to [address]; returns its length. */
        fun applicationProtocol(
            address: Long,
            length: Int,
        ): Int = api.connApplicationProto(handle, address, length)

        /** The queue of this connection's loop: a [DeadlineNode] for [arm] is made on it. */
        val deadlines: DeadlineQueue get() = loop.deadlines

        /** The time [afterNanos] from now, as a deadline's due time. */
        fun dueAfter(afterNanos: Long): Long = clock.nowNanos() + afterNanos

        /** Arms [node], made on [deadlines]; the loop wakes for it if it must. */
        fun arm(node: DeadlineNode) = loop.armDeadline(node)

        /** Hands quiche a datagram that is [length] bytes at [address]. */
        fun receive(
            address: Long,
            length: Int,
        ) {
            api.connRecv(handle, address, length, path.recvInfo)
        }
    }

    /** Any thread. Puts the connection in the loop's next pass: a new client's first flight goes out there. */
    fun start() = scheduleServe()

    /** Any thread. [work] runs inside the connection, on the loop or on whichever thread holds it then. */
    fun submit(work: ConnWork) = submit(work, CallerPlace.HandToLoop)

    /**
     * Any thread. [work] runs inside the connection: on the thread that holds it, or else, when the connection is
     * free, on this thread if [place] allows it and on the loop if not.
     */
    fun submit(
        work: ConnWork,
        place: CallerPlace,
    ) {
        if (inbox.push(work) === Pushed.Refused) return work.gone()
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> counts.leftForHolder()
            WorkLeft.NobodyInside ->
                when (place) {
                    CallerPlace.MayEnter -> enterInPlace()
                    CallerPlace.HandToLoop -> handToLoop()
                }
        }
    }

    /**
     * Loop thread: a datagram for this connection. Inside, quiche reads it from [datagram] now; when a caller is
     * inside, it is copied into work for that caller to run before it leaves, and never dropped.
     */
    fun received(
        datagram: ReceiveBuffer,
        length: Int,
    ) {
        when (touch()) {
            LoopHold.In -> contained { if (!isFailed()) api.connRecv(handle, datagram.address, length, path.recvInfo) }
            LoopHold.Out -> copyForHolder(datagram, length)
        }
    }

    private fun copyForHolder(
        datagram: ReceiveBuffer,
        length: Int,
    ) {
        val slot =
            when (slots) {
                DatagramSlots.None -> Taken.Empty
                is DatagramSlots.Of -> slots.pool.tryTake()
            }
        val work: ConnWork =
            when (slot) {
                is DatagramSlot -> slotWork[slot.index].also { it.fill(datagram, length) }
                else -> FreshRecv(factory.allocate(length)).also { it.fill(datagram, length) }
            }
        if (inbox.push(work) === Pushed.Refused) {
            counts.datagramDropped()
            return work.gone()
        }
        counts.datagramCopied()
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> Unit
            WorkLeft.NobodyInside -> scheduleServe()
        }
    }

    /**
     * Loop thread, once or more per pass after input: run queued work, tell [events] what changed, write output
     * into [out]. Leaves the connection when its output is all collected.
     */
    fun collect(out: SendBatch): Collected {
        if (hold === LoopHold.Out) return Collected.Everything
        if (collectInto(out) === Collected.MoreWaiting) return Collected.MoreWaiting
        return when (owner.tryLeave(loopEntrant)) {
            Leave.Left -> {
                hold = LoopHold.Out
                freeWhenNobodyInside()
                Collected.Everything
            }
            // Work arrived while the loop was inside: collect again in this pass.
            Leave.WorkArrived -> Collected.MoreWaiting
        }
    }

    /** Inside: serves the connection and writes what quiche has to say into [out]. */
    private fun collectInto(out: SendBatch): Collected =
        try {
            if (isFailed()) lastOutput(out, NoCause) else serveAndWrite(out)
        } catch (failure: Exception) {
            fail(failure)
            lastOutput(out, Cause(failure))
        }

    /**
     * A caller, with the connection free, takes a lane and the connection, serves it and sends in place. Without a
     * lane, or when someone entered first, the work is left for them or for the loop.
     */
    private fun enterInPlace() {
        val lane = loop.takeLane()
        if (lane !is CallerSend) return handToLoop()
        when (owner.tryEnter(callerEntrant)) {
            // Whoever entered after the work was pushed runs it before it leaves.
            Entry.Busy -> {
                counts.leftForHolder()
                loop.returnLane(lane)
            }
            Entry.Entered -> {
                counts.enteredInPlace()
                serveAsCaller(lane)
            }
        }
    }

    /**
     * Inside, as a caller: drains work, serves, sends from [first] on this thread and leaves. When the network
     * is full the lane goes to the loop with the unsent datagrams and the loop serves what is left.
     */
    private fun serveAsCaller(first: CallerSend) {
        var lane: Taken = first
        try {
            while (true) {
                val sink = lane
                var more = false
                if (sink is CallerSend) {
                    val collected = collectInto(sink.batch)
                    if (sink.batch.pending > 0 && loop.sendInPlace(sink) === LaneSent.Deferred) {
                        lane = Taken.Empty
                    } else {
                        more = collected === Collected.MoreWaiting
                    }
                } else {
                    serveWithoutOutput()
                }
                if (more) continue
                when (owner.tryLeave(callerEntrant)) {
                    Leave.Left -> break
                    Leave.WorkArrived -> Unit
                }
            }
        } catch (error: Throwable) {
            abandon(error)
        }
        freeWhenNobodyInside()
        val last = lane
        if (last is CallerSend) {
            // Datagrams a failure left collected are not sent: the connection they belong to has ended.
            last.batch.took(last.batch.pending)
            loop.returnLane(last)
        } else {
            handToLoop()
        }
    }

    /** Inside, with no lane: what is left to send is the loop's. */
    private fun serveWithoutOutput() {
        try {
            if (!isFailed()) {
                inbox.drain(runWork)
                serveEvents()
            }
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    /** A caller thread threw an `Error` inside: the connection ends with it, the thread leaves, the `Error` is reported. */
    private fun abandon(error: Throwable) {
        if (lifecycle !is ConnLifecycle.Closed) {
            val end = ConnEnd.CallerFailed(error)
            lifecycle = ConnLifecycle.Closed(end)
            disarm()
            try {
                events.closed(end)
            } catch (late: Exception) {
                error.addSuppressed(late)
            }
            inbox.close(ConnWork::gone)
        }
        while (owner.tryLeave(callerEntrant) === Leave.WorkArrived) inbox.close(ConnWork::gone)
        reportOnThisThread(error)
    }

    private fun handToLoop() {
        counts.handedToLoop()
        scheduleServe()
    }

    private fun serveAndWrite(out: SendBatch): Collected {
        inbox.drain(runWork)
        // Work that ended the connection leaves only its last output.
        if (isFailed()) return lastOutput(out, NoCause)
        serveEvents()
        if (writeOutput(out) === Collected.MoreWaiting) return Collected.MoreWaiting
        rearm()
        return Collected.Everything
    }

    /** What quiche still has to say on the wire, such as the CONNECTION_CLOSE of a failed connection. */
    private fun lastOutput(
        out: SendBatch,
        failed: FailureCause,
    ): Collected =
        try {
            sendHold = SendHold.None
            writeOutput(out)
        } catch (late: Exception) {
            when (failed) {
                is Cause -> failed.cause.addSuppressed(late)
                NoCause -> Unit
            }
            Collected.Everything
        }

    /** Runs [body] inside the connection; what it throws ends this connection and no other. */
    private inline fun contained(body: () -> Unit) {
        try {
            body()
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun isFailed(): Boolean =
        when (val now = lifecycle) {
            is ConnLifecycle.Closed ->
                when (now.end) {
                    is ConnEnd.Failed, is ConnEnd.CallerFailed -> true
                    ConnEnd.TimedOut, is ConnEnd.ByPeer, is ConnEnd.Locally, ConnEnd.Quietly, is ConnEnd.LoopFailed,
                    ConnEnd.ServerClosed,
                    -> false
                }
            ConnLifecycle.Handshaking, ConnLifecycle.Established -> false
        }

    /**
     * Ends the connection with [cause]: closes it on the wire if quiche still lets us, drops queued work and tells
     * [events]. Output is collected once more by the caller.
     */
    private fun fail(cause: Exception) {
        if (lifecycle is ConnLifecycle.Closed) return
        val end = ConnEnd.Failed(cause)
        lifecycle = ConnLifecycle.Closed(end)
        try {
            api.connClose(handle, QuicError.ProcessingFailed)
        } catch (late: Exception) {
            // quiche failed too: the connection still ends locally.
            cause.addSuppressed(late)
        }
        disarm()
        // Ended before the inbox closes, so work answered as gone says how the connection ended.
        try {
            events.closed(end)
        } catch (late: Exception) {
            cause.addSuppressed(late)
        }
        inbox.close(ConnWork::gone)
    }

    /**
     * Loop thread: the loop failed with [cause]. The connection ends with it, inside: on this thread when it is
     * free or the loop holds it, else on the caller that is inside, which runs the work before it leaves.
     */
    fun loopFailed(cause: Throwable) = endWithLoop(ConnEnd.LoopFailed(cause))

    /** Loop thread: the server closed and its loop is stopping after this connection's close was flushed. */
    fun serverClosed() = endWithLoop(ConnEnd.ServerClosed)

    private fun endWithLoop(end: ConnEnd) {
        val work = EndWithLoopWork(end)
        if (inbox.push(work) === Pushed.Refused) return
        when (owner.leaveWork()) {
            WorkLeft.ForHolder -> if (hold === LoopHold.In) finishAsLoop()
            WorkLeft.NobodyInside ->
                when (owner.tryEnter(loopEntrant)) {
                    Entry.Entered -> {
                        hold = LoopHold.In
                        finishAsLoop()
                    }
                    Entry.Busy -> Unit
                }
        }
    }

    /** Loop thread: this connection's socket failed with [cause]. It ends with it; no other connection is touched. */
    fun socketFailed(cause: Exception) = submit(SocketFailedWork(cause))

    /** Loop thread, inside, with the loop stopped: runs what is queued and leaves. */
    private fun finishAsLoop() {
        do {
            inbox.drain(runWork)
        } while (owner.tryLeave(loopEntrant) === Leave.WorkArrived)
        hold = LoopHold.Out
        freeWhenNobodyInside()
    }

    /**
     * Loop thread, after the loop has stopped: frees quiche's connection and its path as soon as nobody is inside
     * it, then tells [then]. Work queued until then, and any submitted later, is told [ConnWork.gone].
     */
    fun release(then: ConnFreed) {
        releasing.store(Release.Requested(then))
        freeWhenNobodyInside()
    }

    /**
     * Whoever leaves the connection calls this: when a release was requested and nobody is inside, the caller
     * enters for good and frees. The word then stays held, so nothing enters a freed connection.
     */
    private fun freeWhenNobodyInside() {
        val requested = releasing.load()
        if (requested !is Release.Requested) return
        when (owner.tryEnter(loopEntrant)) {
            Entry.Busy -> Unit
            Entry.Entered -> {
                releasing.store(Release.Freed)
                inbox.close(ConnWork::gone)
                disarm()
                api.connFree(handle)
                api.recvInfoFree(path.recvInfo)
                api.sendInfoFree(path.sendInfo)
                requested.then.freed()
            }
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
                Entry.Entered -> LoopHold.In.also { loop.entered() }
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
                    api.streamIterNextId(readable).fold(end = { break }, stream = { events.readable(inside, it) })
                }
            } finally {
                api.streamIterFree(readable)
            }
            if (writeWaiters.isNotEmpty()) tellWritable()
            events.served(inside)
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
                api.streamIterNextId(writable).fold(
                    end = { break },
                    stream = { if (writeWaiters.remove(it.id)) events.writable(inside, it) },
                )
            }
        } finally {
            api.streamIterFree(writable)
        }
    }

    /**
     * Writes quiche's output into [out] at the pace quiche asks for. Each datagram carries the time its pacer wants
     * it on the wire; once one is due later than [PACING_ALLOWANCE_NANOS] from now, collecting stops and the
     * connection holds its sending until then ([rearm] arms the deadline). That datagram is already written, so it
     * leaves up to one pacing interval early, and no datagram after it does. The allowance lets a pass send
     * everything due within the next millisecond at once: a pacing interval is often shorter than a loop can sleep,
     * and waking for each datagram would cost more than it smooths.
     */
    private fun writeOutput(out: SendBatch): Collected {
        val now = clock.nowNanos()
        if (sendHold.holdsAt(now)) return Collected.Everything
        sendHold = SendHold.None
        while (out.room() === Room.Open) {
            val offset = out.nextOffset()
            val written = api.connSend(handle, out.address + offset, out.limits.maxDatagramSize, path.sendInfo)
            if (written <= 0) return Collected.Everything
            out.commit(written, path.target)
            val due = api.sendInfoAtNanos(path.sendInfo)
            if (SendHold.pacesAt(due, now)) {
                sendHold = SendHold(due)
                return Collected.Everything
            }
        }
        return Collected.MoreWaiting
    }

    /**
     * A deadline still armed for the same time or earlier is kept: it fires early, quiche ignores timers that
     * have not expired, and the connection re-arms from quiche's timeout then. Only a timeout that moved earlier
     * arms a new deadline, so a connection under traffic allocates no deadline per pass.
     */
    private fun rearm() {
        val wait = api.connTimeoutNanos(handle)
        val now = clock.nowNanos()
        val held = sendHold
        val due =
            when {
                wait == NO_TIMEOUT_NANOS -> if (held.isHolding) held.dueNanos else return
                held.isHolding -> minOf(now + wait, held.dueNanos)
                else -> now + wait
            }
        when (val armed = timer) {
            is TimerState.Armed -> if (armed.node.isArmed() && armed.node.dueNanos <= due) return
            TimerState.Unarmed -> Unit
        }
        disarm()
        timer = TimerState.Armed(loop.armDeadline(due, onTimer))
    }

    private fun disarm() {
        when (val armed = timer) {
            TimerState.Unarmed -> Unit
            is TimerState.Armed -> armed.node.disarm()
        }
        timer = TimerState.Unarmed
    }

    /** Loop thread: the connection's deadline fell due. Quiche's timeout runs inside, with the other work. */
    private fun timerFired() {
        if (timerQueued.compareAndSet(IDLE, QUEUED)) submit(timerWork)
    }

    private fun endOf(): ConnEnd {
        if (api.connIsTimedOut(handle)) return ConnEnd.TimedOut
        api.connPeerError(handle)?.let { return ConnEnd.ByPeer(it) }
        api.connLocalError(handle)?.let { return ConnEnd.Locally(it) }
        return ConnEnd.Quietly
    }

    /** Inside: quiche's timeout, and a connection with no deadline of its own armed. */
    private inner class TimerWork : ConnWork() {
        override fun run(inside: Inside) {
            timerQueued.store(IDLE)
            when (val armed = timer) {
                is TimerState.Armed -> if (!armed.node.isArmed()) timer = TimerState.Unarmed
                TimerState.Unarmed -> Unit
            }
            if (!isFailed()) api.connOnTimeout(handle)
        }

        override fun gone() = timerQueued.store(IDLE)
    }

    /** Inside: a datagram that arrived while a caller was inside, copied into the slot this work is for. */
    private inner class SlotRecv(
        private val pool: FixedPool<DatagramSlot>,
        private val slot: DatagramSlot,
    ) : ConnWork() {
        private var length = 0

        fun fill(
            datagram: ReceiveBuffer,
            length: Int,
        ) {
            this.length = length
            copyDatagram(datagram, length, slot.buffer)
        }

        override fun run(inside: Inside) {
            try {
                if (!isFailed()) inside.receive(slot.address, length)
            } finally {
                pool.give(slot)
            }
        }

        override fun gone() = pool.give(slot)
    }

    /** Inside: a datagram copied into a buffer from the connection's factory, because every slot was in use. */
    private inner class FreshRecv(
        private val buffer: PlatformBuffer,
    ) : ConnWork() {
        private var length = 0

        fun fill(
            datagram: ReceiveBuffer,
            length: Int,
        ) {
            this.length = length
            copyDatagram(datagram, length, buffer)
        }

        override fun run(inside: Inside) {
            try {
                if (!isFailed()) inside.receive(addressOf(buffer), length)
            } finally {
                buffer.freeNativeMemory()
            }
        }

        override fun gone() = buffer.freeNativeMemory()
    }

    /** Inside, once: the connection's socket failed, so the connection ends. */
    private inner class SocketFailedWork(
        private val cause: Exception,
    ) : ConnWork() {
        override fun run(inside: Inside) = fail(cause)

        override fun gone() = Unit
    }

    /** Inside, once: the loop is stopping, so the connection ends with [end]. */
    private inner class EndWithLoopWork(
        private val end: ConnEnd,
    ) : ConnWork() {
        override fun run(inside: Inside) {
            if (lifecycle is ConnLifecycle.Closed) return
            lifecycle = ConnLifecycle.Closed(end)
            disarm()
            try {
                events.closed(end)
            } catch (late: Exception) {
                when (end) {
                    is ConnEnd.LoopFailed -> end.cause.addSuppressed(late)
                    else -> reportOnThisThread(late)
                }
            }
        }

        override fun gone() = Unit
    }
}

private fun copyDatagram(
    datagram: ReceiveBuffer,
    length: Int,
    into: PlatformBuffer,
) {
    into.setLimit(into.capacity)
    into.position(0)
    datagram.buffer.position(0)
    datagram.buffer.setLimit(length)
    into.write(datagram.buffer)
}

/**
 * The time before which the connection sends nothing, because quiche's pacer scheduled its last datagram then.
 * [None] when it is not holding. A value class: it lives in a field and is replaced on every pacing step.
 */
@kotlin.jvm.JvmInline
private value class SendHold(
    val dueNanos: Long,
) {
    val isHolding: Boolean get() = dueNanos != NOT_HOLDING

    /** Whether the hold still keeps datagrams in at [now]: the next one is due later than the allowance. */
    fun holdsAt(now: Long): Boolean = isHolding && dueNanos - now > PACING_ALLOWANCE_NANOS

    companion object {
        val None = SendHold(NOT_HOLDING)

        /**
         * Whether a datagram due at [due] has to wait at [now]. A time further ahead than [PACING_LIMIT_NANOS] is not
         * a pacing time (a clock libquiche reads that this loop's does not): the datagram goes at once.
         */
        fun pacesAt(
            due: Long,
            now: Long,
        ): Boolean = due - now in (PACING_ALLOWANCE_NANOS + 1)..PACING_LIMIT_NANOS
    }
}

private const val NOT_HOLDING = Long.MIN_VALUE

/** A datagram due this soon is sent now. */
internal const val PACING_ALLOWANCE_NANOS = 1_000_000L

/** Quiche paces within a round trip; a delay beyond this is not pacing and is not obeyed. */
private const val PACING_LIMIT_NANOS = 1_000_000_000L

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
    override fun run(loop: LoopHost) = conn.touchedByLoop()

    override fun gone() = Unit
}

/** The failure a last output belongs to, so a further failure is kept with it. */
private sealed interface FailureCause

private object NoCause : FailureCause

private class Cause(
    val cause: Exception,
) : FailureCause

private const val IDLE = 0
private const val QUEUED = 1
private const val CALLER_TOKEN = 1L
