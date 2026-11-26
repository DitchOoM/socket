@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.LentDrop
import com.ditchoom.socket.quic.core.udp.LentDrops
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.udp.DatagramSendError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.seconds

/** How a lent channel stands: open, closed by its owner, or ended by what a receive or a send on it threw. */
internal sealed interface LentEnd {
    data object Open : LentEnd

    data object Closed : LentEnd

    class Threw(
        val thrown: Thrown,
    ) : LentEnd
}

/** Whether the loop asked the pump to stop. */
internal enum class Pumping { On, Closing }

/** Whether the loop has been woken for the arrivals it has not read yet. */
private enum class Woken { No, Yes }

/** Whether the loop waits for a send slot to free, and so must be woken when one does. */
private enum class Blocked { Flowing, Waiting }

/**
 * A started lent io's two coroutines and the rings between them and the loop, on [Dispatchers.Default]. The receiver
 * copies each datagram the channel returns into [arrivals] and gives the channel's buffer back on its own thread; the
 * loop is woken once for every run of arrivals it has not read. The sender sends what the loop put in [outbox],
 * parked between runs in a [WaiterCell], which allocates nothing; a send the channel refuses drops that datagram only,
 * and a refusal from a closed channel ends the pump. Each drop is counted in [drops]. Started on the loop thread when
 * the endpoint joins its loop, over [slots] its io made on the setup lane; nothing here allocates or raises.
 */
@Queue
internal class LentPump(
    private val channel: LentChannel,
    private val peers: LentPeers,
    slots: List<NativeBytes>,
    private val drops: LentDrops,
    /** Any thread: the loop has news to read. */
    private val wake: () -> Unit,
) {
    val arrivals = LentRing(slots.subList(0, LENT_SLOTS), channel.local)
    val outbox = LentRing(slots.subList(LENT_SLOTS, 2 * LENT_SLOTS), channel.local)
    private val end = AtomicReference<LentEnd>(LentEnd.Open)
    private val pumping = AtomicReference(Pumping.On)
    private val blocked = AtomicReference(Blocked.Flowing)
    private val woken = AtomicReference(Woken.No)
    private val sender = WaiterCell()

    /** Receiver only: the form it last found the client's peer in, beside the two setup made. */
    private var learned: Learned = Learned.Nothing

    /** The receiver's end, the sender's and [close] each count once: the third lets go of the channel's claim. */
    private val usersOut = AtomicInt(0)

    // What a coroutine throws is recorded as the channel's end by its completion, so nothing is left to handle here.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private val receiving: Job = scope.launch { receiveAll() }
    private val sending: Job = scope.launch { sendAll() }

    init {
        receiving.invokeOnCompletion { thrown ->
            end.compareAndSet(LentEnd.Open, if (thrown == null) LentEnd.Closed else LentEnd.Threw(Thrown(thrown)))
            wake()
            arrivals.leave()
            userOut()
        }
        sending.invokeOnCompletion { thrown ->
            if (thrown != null) end.compareAndSet(LentEnd.Open, LentEnd.Threw(Thrown(thrown)))
            wake()
            outbox.leave()
            userOut()
        }
    }

    /** Loop thread, before it reads the arrivals: the next arrival wakes it again. */
    fun reading() = woken.store(Woken.No)

    /** Loop thread, after it put datagrams in [outbox]. */
    fun offered() = sender.resumeWaiting()

    /** How the channel stands. */
    fun end(): LentEnd = end.load()

    /** Loop thread: [outbox] had no room for the rest of a batch; the sender wakes the loop when a slot frees. */
    fun full() {
        blocked.store(Blocked.Waiting)
        // A slot freed before the mark found nothing to wake, so the loop is told now.
        if (outbox.hasRoom() && blocked.compareAndSet(Blocked.Waiting, Blocked.Flowing)) wake()
    }

    /**
     * Loop thread, once: the receiver stops, and the sender sends what it holds, the connection's CONNECTION_CLOSE
     * included, for at most [DRAIN_BOUND]; then it is cancelled, so a send that never returns holds nothing for ever.
     */
    fun close() {
        if (!pumping.compareAndSet(Pumping.On, Pumping.Closing)) return
        receiving.cancel()
        sender.resumeWaiting()
        scope.launch {
            delay(DRAIN_BOUND)
            sending.cancel()
        }
        arrivals.leave()
        outbox.leave()
        userOut()
    }

    /** Whether both coroutines have ended: tests. */
    fun ended(): Boolean = receiving.isCompleted && sending.isCompleted

    private fun userOut() {
        if (usersOut.incrementAndFetch() != USERS) return
        scope.cancel()
        channel.claim.pumpEnded()
    }

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
        val dropped =
            when {
                !admits(got) -> LentDrop.Foreign
                length > MAX_DATAGRAM -> LentDrop.TooLarge
                !arrivals.hasRoom() -> LentDrop.ArrivalsFull
                else -> {
                    arrivals.next().copyIn(payload, length)
                    arrivals.publish(length, got.datagram.peer)
                    if (woken.exchange(Woken.Yes) == Woken.No) wake()
                    return
                }
            }
        drops.dropped(dropped)
    }

    /**
     * Receiver only: whether a datagram is from the connection's peer. A client's peer may arrive in a form neither
     * setup made (a dual-stack socket reports an IPv4 peer IPv4-mapped): it is converted once, and that form is
     * remembered, so only a stranger's datagram costs a conversion.
     */
    private fun admits(got: DatagramReadResult.Received): Boolean {
        val source = got.datagram.peer
        return when (peers) {
            is LentPeers.One ->
                peers.isFrom(source) || learned.isForm(source) || learns(source, peers.key)
            is LentPeers.Every -> true
        }
    }

    private fun learns(
        source: SocketAddress,
        key: PathKey,
    ): Boolean {
        if (!isSamePeer(source.toPathKey(), key)) return false
        learned = Learned.Form(source)
        return true
    }

    /** Sends what the loop offered until [close], then what is left: a close seen means every offer before it is seen. */
    private suspend fun sendAll() {
        while (true) {
            while (!outbox.isEmpty()) {
                val buffer = outbox.head().owner
                buffer.position(0)
                buffer.setLimit(outbox.headLength())
                sendContained(refused = ::refused) { channel.send(buffer, outbox.headAddress()) }
                outbox.pop()
                if (blocked.compareAndSet(Blocked.Waiting, Blocked.Flowing)) wake()
                if (!channel.isOpen()) {
                    end.compareAndSet(LentEnd.Open, LentEnd.Closed)
                    return
                }
            }
            when (pumping.load()) {
                Pumping.On -> suspendCoroutineUninterceptedOrReturn<Unit> { caller -> sender.park(caller) { settled() } }
                Pumping.Closing -> if (outbox.isEmpty()) return
            }
        }
    }

    /** A send the channel refused: counted by its kind while the channel is open; a closed one ends the pump instead. */
    private fun refused(error: DatagramSendError) {
        if (channel.isOpen()) drops.dropped(dropOf(error))
    }

    private fun settled(): Boolean = !outbox.isEmpty() || pumping.load() == Pumping.Closing

    private companion object {
        const val USERS = 3

        /** How long a closed pump's sender may take to send what it holds. */
        val DRAIN_BOUND = 1.seconds
    }
}

/** The kind of drop a send refused for [error] is. */
private fun dropOf(error: DatagramSendError): LentDrop =
    when (error) {
        is DatagramSendError.TooLarge -> LentDrop.SendTooLarge
        is DatagramSendError.Unreachable, is DatagramSendError.PortUnreachable -> LentDrop.SendUnreachable
        is DatagramSendError.WouldBlock -> LentDrop.SendWouldBlock
        is DatagramSendError.NotPermitted, is DatagramSendError.SourceAddressUnavailable, is DatagramSendError.OsError,
        is DatagramSendError.PlatformError, is DatagramSendError.Transport,
        -> LentDrop.SendRefused
    }

/** The form a lent client's receiver found its peer in, beyond the two its setup made. */
private sealed interface Learned {
    data object Nothing : Learned

    class Form(
        val address: SocketAddress,
    ) : Learned
}

private fun Learned.isForm(source: SocketAddress): Boolean =
    when (this) {
        is Learned.Nothing -> false
        is Learned.Form -> address == source
    }

/** Datagrams each direction holds between the loop and the channel's coroutines. */
internal const val LENT_SLOTS = 32

/**
 * Setup lane: the slots of both of a lent io's rings, of [factory]'s memory, each with an address both sides copy by; a
 * factory without addressable memory frees the slots made so far and fails the setup there.
 */
internal fun lentSlotsOf(factory: BufferFactory): List<NativeBytes> {
    val made = ArrayList<NativeBytes>(2 * LENT_SLOTS)
    repeat(2 * LENT_SLOTS) {
        val buffer = factory.allocate(MAX_DATAGRAM)
        when (val addressed = NativeBytes.of(buffer)) {
            is Addressed.Native -> made += addressed.bytes
            is Addressed.NotAddressable -> {
                for (slot in made) slot.free()
                buffer.freeNativeMemory()
                raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
            }
        }
    }
    return made
}
