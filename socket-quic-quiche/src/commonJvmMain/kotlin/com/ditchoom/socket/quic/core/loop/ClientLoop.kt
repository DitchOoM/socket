@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.DeadlineQueue
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.conn.OneConnParts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.DatagramVisitor
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.Runnable
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.function.Consumer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a [ClientEndpoint] stands in a loop's intrusive lists. */
internal sealed interface EndpointLink {
    /** Not in the list. */
    object NotQueued : EndpointLink

    /** The last of the list. */
    object End : EndpointLink
}

/** Told once, on the loop thread, after an endpoint's socket is closed and deregistered. */
internal fun interface EndpointClosed {
    fun closed()
}

/** Why every connection of a [ClientLoop] ended: its owner stopped it. */
internal class ClientLoopStopped : Exception()

/** A connection's socket failed with [error]; [cause] is the platform's own exception. */
internal class SocketFailure(
    val error: UdpError,
    cause: Throwable,
) : Exception(cause)

private sealed interface Registration {
    object None : Registration

    class Of(
        val key: SelectionKey,
    ) : Registration
}

internal sealed interface EndpointPhase {
    /** Built, not yet on the loop. */
    object Fresh : EndpointPhase

    object Live : EndpointPhase

    /** The socket failed: it is no longer used, and the connection is ending. */
    object Dead : EndpointPhase

    /** Deregistered and closed. */
    object Gone : EndpointPhase
}

internal sealed interface EndpointClosing {
    object Open : EndpointClosing

    class Ending(
        val closed: EndpointClosed,
    ) : EndpointClosing
}

internal sealed interface Served {
    object Fine : Served

    class SocketDied(
        val failure: SocketFailure,
    ) : Served
}

/**
 * One client connection's socket on a [ClientLoop]: its own connected channel, its own send batch and caller lanes,
 * and the connection's parts. Callers in place send through it; the loop receives for it and collects its output.
 */
internal class ClientEndpoint(
    private val loop: ClientLoop,
    internal val channel: DatagramChannel,
    internal val io: DatagramBatchIo,
    private val send: SendBatch,
    private val lanes: FixedPool<CallerSend>,
) : ConnHost,
    LaneHost,
    EndpointLink {
    val parts = OneConnParts()
    internal val visitor = DatagramVisitor(parts::onDatagram)

    // Loop thread only from here on.
    internal var nextReady: EndpointLink = EndpointLink.NotQueued
    internal var nextTouched: EndpointLink = EndpointLink.NotQueued
    internal var slot = NOT_IN_LOOP
    internal var phase: EndpointPhase = EndpointPhase.Fresh
    internal var closing: EndpointClosing = EndpointClosing.Open
    private var registration: Registration = Registration.None
    private var interest = SelectionKey.OP_READ
    private var waiting: LaneLink = LaneLink.End
    private var waitingLast: LaneLink = LaneLink.End
    private val handOffs: Array<AdoptLane> = Array(lanes.size) { AdoptLane(this, lanes[it]) }
    private val closedWork = SocketClosedWork(this)

    override val counts: LoopCounts get() = loop.counts
    override val deadlines: DeadlineQueue get() = loop.deadlines

    override fun submit(work: LoopWork) = loop.submit(work)

    override fun armDeadline(
        dueNanos: Long,
        action: Runnable,
    ): DeadlineNode = loop.armDeadline(dueNanos, action)

    override fun armDeadline(node: DeadlineNode) = loop.armDeadline(node)

    override fun takeLane(): Taken = lanes.tryTake()

    override fun returnLane(lane: CallerSend) = lanes.give(lane)

    override fun entered() = loop.touch(this)

    /** Any thread. This endpoint's socket is closed and deregistered, then [closed] is told, on the loop. */
    fun close(closed: EndpointClosed) = loop.submit(CloseEndpoint(this, closed))

    /** Any thread holding [lane]: sends through this connection's own socket. */
    override fun sendInPlace(lane: CallerSend): LaneSent {
        val batch = lane.batch
        val callerCounts = loop.callerCounts
        while (batch.pending > 0) {
            val before = batch.pending
            when (val result = io.send(batch)) {
                SendBatchResult.AllTaken -> {
                    callerCounts.sent(before)
                    batch.took(before)
                }
                is SendBatchResult.Partial -> {
                    callerCounts.sent(result.taken)
                    if (result.why === UdpError.WouldBlock) {
                        batch.took(result.taken)
                        callerCounts.deferred()
                        loop.submit(handOffs[lane.index])
                        return LaneSent.Deferred
                    }
                    if (result.why === UdpError.SocketClosed) loop.submit(closedWork)
                    batch.took(result.taken + 1)
                }
            }
        }
        return LaneSent.Sent
    }

    /** Loop thread: [lane] holds datagrams to send when the socket can take them. */
    override fun adopt(lane: CallerSend) {
        lane.nextLane = LaneLink.End
        when (val last = waitingLast) {
            is CallerSend -> last.nextLane = lane
            LaneLink.End -> waiting = lane
        }
        waitingLast = lane
        loop.touch(this)
    }

    /** Loop thread, once, before the first pass that serves it. */
    internal fun register(selector: Selector) {
        registration = Registration.Of(channel.register(selector, SelectionKey.OP_READ, this))
        phase = EndpointPhase.Live
    }

    /** Loop thread: receives up to one batch into [batch] and hands it to the connection. */
    internal fun receive(
        batch: ReceiveBatch,
        counts: LoopCounts,
    ): Served {
        when (val got = io.receive(batch)) {
            ReceiveBatchResult.Got -> {
                counts.datagramsIn += batch.count
                batch.forEachDatagram(visitor)
            }
            ReceiveBatchResult.Nothing -> Unit
            is ReceiveBatchResult.Failed -> {
                counts.receiveFailures++
                batch.forEachDatagram(visitor)
                return fatal(got.error)
            }
        }
        return Served.Fine
    }

    /** Loop thread: sends what lanes still hold, then collects the connection's output and sends it. */
    internal fun serve(counts: LoopCounts): Served {
        if (phase !== EndpointPhase.Dead) {
            val lanesSent = flushLanes(counts)
            if (lanesSent is Served.SocketDied) return lanesSent
        }
        while (true) {
            val collected = parts.collect(send)
            if (send.pending > 0) {
                if (phase === EndpointPhase.Dead) {
                    discard()
                } else {
                    val sent = flush(counts)
                    if (sent is Served.SocketDied) return sent
                }
            }
            if (collected === Collected.Everything || send.pending > 0) break
        }
        return Served.Fine
    }

    /** What a dead socket cannot send is dropped; the connection's close is already on its way. */
    private fun discard() {
        send.took(send.pending)
        while (true) {
            val lane = waiting
            if (lane !is CallerSend) break
            lane.batch.took(lane.batch.pending)
            waiting = lane.nextLane
            lane.nextLane = LaneLink.End
            lanes.give(lane)
        }
        waitingLast = LaneLink.End
    }

    private fun flush(counts: LoopCounts): Served {
        val before = send.pending
        when (val result = io.send(send)) {
            SendBatchResult.AllTaken -> {
                counts.datagramsOut += before
                send.took(before)
            }
            is SendBatchResult.Partial -> {
                counts.datagramsOut += result.taken
                counts.sendRefusals++
                // Only a full socket is worth waiting for. Any other refusal is that datagram's alone, except a
                // closed socket, which ends the connection.
                if (result.why === UdpError.SocketClosed) return fatal(result.why)
                send.took(if (result.why === UdpError.WouldBlock) result.taken else result.taken + 1)
            }
        }
        return Served.Fine
    }

    /** Sends what lanes still hold, oldest lane first, until the network is full again. */
    private fun flushLanes(counts: LoopCounts): Served {
        while (true) {
            val lane = waiting
            if (lane !is CallerSend) return Served.Fine
            val before = lane.batch.pending
            when (val result = io.send(lane.batch)) {
                SendBatchResult.AllTaken -> {
                    counts.datagramsOut += before
                    lane.batch.took(before)
                    waiting = lane.nextLane
                    if (waiting === LaneLink.End) waitingLast = LaneLink.End
                    lane.nextLane = LaneLink.End
                    lanes.give(lane)
                }
                is SendBatchResult.Partial -> {
                    counts.datagramsOut += result.taken
                    counts.sendRefusals++
                    if (result.why === UdpError.WouldBlock) {
                        lane.batch.took(result.taken)
                        return Served.Fine
                    }
                    if (result.why === UdpError.SocketClosed) return fatal(result.why)
                    lane.batch.took(result.taken + 1)
                }
            }
        }
    }

    private fun fatal(error: UdpError): Served =
        when (error) {
            UdpError.SocketClosed -> Served.SocketDied(SocketFailure(error, ClosedChannelException()))
            is UdpError.Other -> Served.SocketDied(SocketFailure(error, error.cause))
            // The rest are one datagram's alone, as the one-socket loop treats them.
            UdpError.WouldBlock,
            UdpError.MessageTooLarge,
            UdpError.NetworkUnreachable,
            UdpError.HostUnreachable,
            UdpError.PortUnreachable,
            UdpError.NoBufferSpace,
            UdpError.PermissionDenied,
            -> Served.Fine
        }

    /** Loop thread: the socket is waited on for reading, and for writing only while the network refused a send. */
    internal fun refreshInterest() {
        val registered = registration
        if (registered !is Registration.Of) return
        val wanted =
            when {
                phase !== EndpointPhase.Live -> 0
                send.pending > 0 || waiting is CallerSend -> SelectionKey.OP_READ or SelectionKey.OP_WRITE
                else -> SelectionKey.OP_READ
            }
        if (wanted != interest) {
            try {
                registered.key.interestOps(wanted)
                interest = wanted
            } catch (cancelled: CancelledKeyException) {
                // The channel was closed under the loop: the socket is gone, and the connection ends with it.
                if (phase === EndpointPhase.Live) died(SocketFailure(UdpError.SocketClosed, cancelled))
            }
        }
    }

    /** Loop thread: a thread sending in place found the socket closed. */
    internal fun foundClosed() {
        if (phase === EndpointPhase.Live) died(SocketFailure(UdpError.SocketClosed, ClosedChannelException()))
    }

    /**
     * Loop thread: the socket failed. It is no longer used and not waited on; the connection ends with [failure]. The
     * loop may itself be inside the connection, which then leaves the failure for itself to run, so the endpoint is
     * served again to run it.
     */
    internal fun died(failure: Exception) {
        phase = EndpointPhase.Dead
        parts.socketFailed(failure)
        loop.touch(this)
    }

    /** Loop thread: the loop ended with [cause]; the connection ends with it, and the socket is no longer used. */
    internal fun loopEnded(cause: Throwable) {
        phase = EndpointPhase.Dead
        parts.loopFailed(cause)
    }

    /** Loop thread: deregisters and closes the socket. */
    internal fun release() {
        phase = EndpointPhase.Gone
        when (val registered = registration) {
            Registration.None -> Unit
            is Registration.Of -> registered.key.cancel()
        }
        io.close()
    }
}

/** Loop work: a new connection's socket joins the loop. */
private class AttachEndpoint(
    private val loop: ClientLoop,
    private val endpoint: ClientEndpoint,
) : LoopWork() {
    override fun run(loop: LoopHost) = this.loop.attach(endpoint)

    override fun gone() = loop.unplaced(endpoint)
}

/** Loop work: a thread sending in place found the endpoint's socket closed. */
private class SocketClosedWork(
    private val endpoint: ClientEndpoint,
) : LoopWork() {
    override fun run(loop: LoopHost) = endpoint.foundClosed()

    override fun gone() = Unit
}

/** Loop work: a connection ended, so its socket leaves the loop. */
private class CloseEndpoint(
    private val endpoint: ClientEndpoint,
    private val closed: EndpointClosed,
) : LoopWork() {
    override fun run(loop: LoopHost) {
        endpoint.closing = EndpointClosing.Ending(closed)
        (loop as ClientLoop).touch(endpoint)
    }

    // The loop has stopped: the endpoint was never served again, so its socket is closed here.
    override fun gone() {
        endpoint.release()
        closed.closed()
    }
}

/** Loop work: the loop's owner stops it. */
private class StopClientLoop(
    private val closed: LoopClosed,
) : LoopWork() {
    override fun run(loop: LoopHost) = (loop as ClientLoop).beginClosing(closed)

    override fun gone() = closed.closed(LoopCounts())
}

/** What a [ClientLoop] is: [Alive] until it stops, from its owner or from a failure. */
internal sealed interface ClientLoopLife {
    object Alive : ClientLoopLife

    object Stopped : ClientLoopLife
}

/**
 * One platform thread's run-to-completion loop over many client connections, each with its own connected socket
 * registered with this loop's one selector. A pass: fire due deadlines, receive from every ready socket into one
 * shared batch and hand the datagrams to their connections, run queued work, then collect the output of each
 * connection that was touched and send it through that connection's own socket.
 *
 * A socket that fails ends its connection only; a failure of the loop itself ends every connection on it.
 * [receive] and [storage] belong to the loop and are freed when it stops.
 */
internal class ClientLoop(
    val name: String,
    /** Distinguishes this loop's turns in connection ownership words. */
    val id: Int,
    private val clock: LoopClock,
    private val receive: ReceiveBatch,
    private val storage: List<PlatformBuffer>,
) : LoopHost,
    Placement {
    override val counts = LoopCounts()
    val callerCounts = CallerCounts()
    val deadlines = DeadlineQueue()

    /** Connections placed here, counted when placed and not when they end: what placement balances. */
    val connections = AtomicInt(0)
    val life = AtomicReference<ClientLoopLife>(ClientLoopLife.Alive)

    private val selector: Selector = Selector.open()
    private val scan: ReadinessScan = ReadinessScan.ofThisRuntime()
    private val inbox = WorkInbox<LoopWork>()
    private val sleepsUntil = AtomicLong(NO_DEADLINE)
    private val sleep = AtomicInt(AWAKE)
    private var state: LoopState = LoopState.Running
    private val runWork: (LoopWork) -> Unit = { it.run(this) }
    private val endpoints = ArrayList<ClientEndpoint>()
    private var readyHead: EndpointLink = EndpointLink.End
    private var touchedHead: EndpointLink = EndpointLink.End

    private val onReady =
        Consumer<SelectionKey> { key ->
            val endpoint = key.attachment() as ClientEndpoint
            try {
                val ops = key.readyOps()
                if (ops and SelectionKey.OP_READ != 0) pushReady(endpoint)
                if (ops and SelectionKey.OP_WRITE != 0) touch(endpoint)
            } catch (cancelled: CancelledKeyException) {
                // Someone closed the channel: the receive finds it closed, and ends that connection alone.
                pushReady(endpoint)
            }
        }

    /** Any thread. */
    override fun submit(work: LoopWork) {
        if (inbox.push(work) === Pushed.Refused) return work.gone()
        wakeIfAsleep()
    }

    /** Any thread. Arms a deadline for [dueNanos]; the loop is woken only if it sleeps towards a later time. */
    fun armDeadline(
        dueNanos: Long,
        action: Runnable,
    ): DeadlineNode {
        val node = deadlines.arm(dueNanos, action)
        if (dueNanos < sleepsUntil.load()) wakeIfAsleep()
        return node
    }

    /** Any thread. Arms [node], made on [deadlines], with the same wake rule. */
    fun armDeadline(node: DeadlineNode) {
        deadlines.arm(node)
        if (node.dueNanos < sleepsUntil.load()) wakeIfAsleep()
    }

    /** Any thread. [endpoint], built, joins this loop; its connection may start once this returns. */
    fun place(endpoint: ClientEndpoint) = submit(AttachEndpoint(this, endpoint))

    /** Any thread. [closed] is told once the loop has stopped and ended every connection on it. */
    fun close(closed: LoopClosed) = submit(StopClientLoop(closed))

    /** Loop thread: [endpoint] is in this pass's collecting. */
    internal fun touch(endpoint: ClientEndpoint) {
        if (endpoint.nextTouched !== EndpointLink.NotQueued || endpoint.phase === EndpointPhase.Gone) return
        endpoint.nextTouched = touchedHead
        touchedHead = endpoint
    }

    private fun pushReady(endpoint: ClientEndpoint) {
        if (endpoint.nextReady !== EndpointLink.NotQueued) return
        endpoint.nextReady = readyHead
        readyHead = endpoint
    }

    internal fun attach(endpoint: ClientEndpoint) {
        try {
            endpoint.register(selector)
        } catch (failure: Exception) {
            return endpoint.died(failure)
        }
        endpoint.slot = endpoints.size
        endpoints.add(endpoint)
    }

    /** An endpoint that never joined the loop is not counted against it. */
    internal fun unplaced(endpoint: ClientEndpoint) {
        connections.fetchAndAdd(-1)
        endpoint.loopEnded(ClientLoopStopped())
    }

    internal fun beginClosing(closed: LoopClosed) {
        state = LoopState.Closing(closed)
    }

    private fun wakeIfAsleep() {
        if (sleep.load() == ASLEEP && sleep.compareAndSet(ASLEEP, WOKEN)) {
            callerCounts.woke()
            selector.wakeup()
        }
    }

    /** Loop thread only, until stopped: the body a platform's loop thread runs. */
    fun run() {
        try {
            while (true) {
                val next = pass(clock.nowNanos())
                when (val now = state) {
                    LoopState.Running -> sleepUntil(next)
                    is LoopState.Closing -> return finish(now.closed)
                }
            }
        } catch (failure: Throwable) {
            // Reported, not rethrown: every connection on this loop ends with it as the cause.
            stopAfter(failure)
        }
    }

    /** One pass at [now]. Returns when the loop next needs to run without input. */
    fun pass(now: Long): NextDue {
        counts.passes++
        deadlines.fireDue(now)
        while (true) {
            val ready = readyHead
            if (ready !is ClientEndpoint) break
            readyHead = ready.nextReady
            ready.nextReady = EndpointLink.NotQueued
            receiveOn(ready)
        }
        counts.work += inbox.drain(runWork)
        while (true) {
            val touched = touchedHead
            if (touched !is ClientEndpoint) break
            touchedHead = touched.nextTouched
            touched.nextTouched = EndpointLink.NotQueued
            serve(touched)
        }
        return deadlines.nextDue(now)
    }

    private fun receiveOn(endpoint: ClientEndpoint) {
        if (endpoint.phase !== EndpointPhase.Live) return
        val result =
            try {
                endpoint.receive(receive, counts)
            } catch (failure: Exception) {
                return endpoint.died(failure)
            }
        if (result is Served.SocketDied) endpoint.died(result.failure)
    }

    private fun serve(endpoint: ClientEndpoint) {
        val result =
            try {
                endpoint.serve(counts)
            } catch (failure: Exception) {
                endpoint.died(failure)
                Served.Fine
            }
        if (result is Served.SocketDied) endpoint.died(result.failure)
        when (val ending = endpoint.closing) {
            EndpointClosing.Open -> endpoint.refreshInterest()
            is EndpointClosing.Ending -> leave(endpoint, ending.closed)
        }
    }

    private fun leave(
        endpoint: ClientEndpoint,
        closed: EndpointClosed,
    ) {
        if (endpoint.phase === EndpointPhase.Gone) return
        if (endpoint.slot != NOT_IN_LOOP) {
            val last = endpoints.removeAt(endpoints.size - 1)
            if (last !== endpoint) {
                endpoints[endpoint.slot] = last
                last.slot = endpoint.slot
            }
        }
        endpoint.release()
        connections.fetchAndAdd(-1)
        closed.closed()
    }

    private fun sleepUntil(next: NextDue) {
        sleepsUntil.store(
            next.fold(none = { NO_DEADLINE }, due = { it }),
        )
        sleep.store(ASLEEP)
        // A deadline armed, or work pushed, before ASLEEP was published is seen here; one after it sees ASLEEP.
        if (inbox.looksEmpty() && !deadlines.hasInbox()) {
            counts.waits++
            next.fold(
                none = { scan.select(selector, onReady) },
                due = { due ->
                    val nanos = due - clock.nowNanos()
                    // The selector counts whole milliseconds; a shorter wait rounds up so a deadline is never early.
                    if (nanos <=
                        0
                    ) {
                        scan.selectNow(selector, onReady)
                    } else {
                        scan.select(selector, (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI, onReady)
                    }
                },
            )
        }
        sleep.store(AWAKE)
    }

    private fun finish(closed: LoopClosed) {
        stopAfter(ClientLoopStopped())
        closed.closed(counts)
    }

    /** Connections first, so their own close requests are queued before the inbox closes and runs them as gone. */
    private fun stopAfter(failure: Throwable) {
        life.store(ClientLoopLife.Stopped)
        for (endpoint in endpoints) endpoint.loopEnded(failure)
        for (endpoint in endpoints) endpoint.release()
        endpoints.clear()
        selector.close()
        inbox.close(LoopWork::gone)
        storage.forEach(PlatformBuffer::freeNativeMemory)
    }

    /** Releases a loop that never started. */
    fun discard() {
        selector.close()
        storage.forEach(PlatformBuffer::freeNativeMemory)
    }
}

private const val NOT_IN_LOOP = -1
private const val AWAKE = 0
private const val ASLEEP = 1
private const val WOKEN = 2
private const val NO_DEADLINE = Long.MAX_VALUE
private const val NANOS_PER_MILLI = 1_000_000L
