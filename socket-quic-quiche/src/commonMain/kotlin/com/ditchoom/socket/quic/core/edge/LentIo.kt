@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/** How a lent channel stands: open, ended by its owner, or ended by what a receive or a send on it threw. */
private sealed interface LentEnd {
    data object Open : LentEnd

    data object Closed : LentEnd

    class Threw(
        val thrown: Thrown,
    ) : LentEnd
}

private enum class LentState { Open, Closed }

/** Whether the sender waits for the loop's next offer, and how to wake it. */
private sealed interface Sender {
    data object Running : Sender

    class Waiting(
        val waiting: CancellableContinuation<Unit>,
    ) : Sender
}

/**
 * The [DatagramBatchIo] of a channel the caller lent ([LentChannel]): one socket, which no poller watches and which this
 * io never closes. Its receiver coroutine copies each datagram the channel returns into a slot of [arrivals], gives the
 * channel's buffer back on its own thread and wakes the loop, which copies the slot into its receive buffer. A datagram
 * from a peer the connection does not talk to, or one that finds every slot full, is given back unread. The loop copies
 * each datagram it sends into a slot of [outbox] and wakes the sender coroutine, which sends it on the channel; with
 * every slot taken the rest wait in the batch, and the sender wakes the loop when one frees. Only the loop sends: a
 * connection on a lent channel lends its callers no lane. The loop's side allocates nothing per datagram. [close] stops
 * the receiver and lets the sender finish what it holds, the connection's CONNECTION_CLOSE included.
 */
@Queue
internal class LentIo(
    private val channel: LentChannel,
    private val peers: LentPeers,
    factory: BufferFactory,
    /** Any thread: the loop has news to read. */
    private val wake: () -> Unit,
) : DatagramBatchIo {
    private val book = PeerBook(channel.local)
    private val arrivals = LentRing(List(SLOTS) { slotOf(factory) }, channel.local)
    private val outbox = LentRing(List(SLOTS) { slotOf(factory) }, channel.local)
    private val end = AtomicReference<LentEnd>(LentEnd.Open)
    private val state = AtomicReference(LentState.Open)
    private val sender = AtomicReference<Sender>(Sender.Running)
    private val blocked = AtomicReference(Blocked.Flowing)

    // What a coroutine throws is recorded as the channel's end by its completion, so nothing is left to handle here.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private val receiving: Job = scope.launch { receiveAll() }
    private val sending: Job = scope.launch { sendAll() }

    init {
        receiving.invokeOnCompletion { thrown ->
            end.compareAndSet(LentEnd.Open, if (thrown == null) LentEnd.Closed else LentEnd.Threw(Thrown(thrown)))
            wake()
            arrivals.leave()
        }
        sending.invokeOnCompletion { thrown ->
            if (thrown != null) end.compareAndSet(LentEnd.Open, LentEnd.Threw(Thrown(thrown)))
            wake()
            outbox.leave()
        }
    }

    override val capability: BatchCapability = BatchCapability.OneByOne

    override fun send(batch: SendBatch): SendBatchResult {
        when (state.load()) {
            LentState.Open -> Unit
            LentState.Closed -> return SendBatchResult.Partial(0, UdpError.SocketClosed)
        }
        var taken = 0
        while (taken < batch.pending && outbox.hasRoom()) {
            when (val target = batch.targetOf(taken)) {
                is SendTarget.ConnectedPeer ->
                    when (peers) {
                        is LentPeers.One -> offer(batch, taken, peers.address)
                        // An unconnected socket has no peer of its own: the datagram goes nowhere.
                        is LentPeers.Every -> Unit
                    }
                is SendTarget.ServerReply -> offer(batch, taken, book.addressOf(target.to))
            }
            taken++
        }
        if (taken > 0) wakeSender()
        if (taken == batch.pending) return SendBatchResult.AllTaken
        // A slot freed before the mark found nothing to wake, so the loop is told now.
        blocked.store(Blocked.Waiting)
        if (outbox.hasRoom() && blocked.compareAndSet(Blocked.Waiting, Blocked.Flowing)) wake()
        return SendBatchResult.Partial(taken, UdpError.WouldBlock)
    }

    private fun offer(
        batch: SendBatch,
        n: Int,
        to: SocketAddress,
    ) {
        val length = batch.lengthOf(n)
        outbox.next().copyFrom(batch.storage, batch.offsetOf(n), 0, length)
        outbox.publish(length, to)
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        var got = 0
        while (!arrivals.isEmpty()) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) return ReceiveBatchResult.GotWithBacklog
            val length = arrivals.headLength()
            buffer.bytes.copyFrom(arrivals.head(), 0, 0, length)
            into.received(buffer, length, keyOf(arrivals.headAddress()), ArrivedOn.Unreported)
            arrivals.pop()
            got++
        }
        if (got > 0) return ReceiveBatchResult.Got
        return when (val ended = end.load()) {
            is LentEnd.Open -> ReceiveBatchResult.Nothing
            is LentEnd.Closed -> ReceiveBatchResult.Failed(UdpError.SocketClosed)
            is LentEnd.Threw -> ReceiveBatchResult.Failed(UdpError.Unnamed(ended.thrown))
        }
    }

    /** Loop thread: a client's datagrams all come from its peer; a server's sources are looked up once each. */
    private fun keyOf(from: SocketAddress): PathKey =
        when (peers) {
            is LentPeers.One -> peers.key
            is LentPeers.Every -> book.keyOf(from)
        }

    override fun probe(): SocketProbe =
        when (end.load()) {
            is LentEnd.Open -> SocketProbe.Open
            is LentEnd.Closed, is LentEnd.Threw -> SocketProbe.Closed
        }

    /** The owner's socket is the owner's to follow the network with: it is the same endpoint. */
    override fun reopen(): Reopened = Reopened.SameEndpoint

    /** Ends this io's use of the channel, which stays open: the receiver stops and the sender sends what it holds. */
    override fun close(): SocketClosed {
        if (state.compareAndSet(LentState.Open, LentState.Closed)) {
            receiving.cancel()
            wakeSender()
            arrivals.leave()
            outbox.leave()
        }
        return SocketClosed
    }

    /** Copies what the channel returns into [arrivals] until it closes, or until [close] cancels it. */
    private suspend fun receiveAll() {
        while (true) {
            when (val got = channel.receive()) {
                is DatagramReadResult.Received -> {
                    keep(got)
                    got.datagram.payload.freeIfNeeded()
                }
                is DatagramReadResult.Closed -> return
            }
        }
    }

    private fun keep(got: DatagramReadResult.Received) {
        val payload = got.datagram.payload
        val length = payload.remaining()
        val admitted =
            when (peers) {
                is LentPeers.One -> got.datagram.peer == peers.address
                is LentPeers.Every -> true
            }
        if (!admitted || length > MAX_DATAGRAM || !arrivals.hasRoom()) return
        arrivals.next().copyIn(payload, length)
        arrivals.publish(length, got.datagram.peer)
        wake()
    }

    /** Sends what the loop offered until [close], then what is left: a close seen means every offer before it is seen. */
    private suspend fun sendAll() {
        while (true) {
            sendHeld()
            when (state.load()) {
                LentState.Open -> awaitOffer()
                LentState.Closed -> {
                    sendHeld()
                    return
                }
            }
        }
    }

    // Inline, so the sender's one state machine serves every send: a call of its own would be one more object per wake.
    @Suppress("NOTHING_TO_INLINE")
    private suspend inline fun sendHeld() {
        while (!outbox.isEmpty()) {
            val buffer = outbox.head().owner
            buffer.position(0)
            buffer.setLimit(outbox.headLength())
            channel.send(buffer, outbox.headAddress())
            outbox.pop()
            if (blocked.compareAndSet(Blocked.Waiting, Blocked.Flowing)) wake()
        }
    }

    /** Parks until the loop offers a datagram or closes; one that came before the park was published is seen after it. */
    private suspend fun awaitOffer() =
        suspendCancellableCoroutine { waiting ->
            val parked = Sender.Waiting(waiting)
            sender.store(parked)
            val pending = !outbox.isEmpty() || state.load() == LentState.Closed
            if (pending && sender.compareAndSet(parked, Sender.Running)) waiting.resume(Unit)
        }

    /** Any thread: a parked sender resumes, on its own dispatcher; one already running finds the offer when it loops. */
    private fun wakeSender() {
        when (val seen = sender.load()) {
            is Sender.Running -> Unit
            is Sender.Waiting -> if (sender.compareAndSet(seen, Sender.Running)) seen.waiting.resume(Unit)
        }
    }

    private companion object {
        /** Datagrams each direction holds between the loop and the channel's coroutines. */
        const val SLOTS = 32
    }
}

/** Whether the loop waits for a send slot to free, and so must be woken when one does. */
private enum class Blocked { Flowing, Waiting }

/** One slot of [factory]'s memory, with an address both sides copy by. */
private fun slotOf(factory: BufferFactory): NativeBytes {
    val buffer = factory.allocate(MAX_DATAGRAM)
    return when (val addressed = NativeBytes.of(buffer)) {
        is Addressed.Native -> addressed.bytes
        is Addressed.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
    }
}
