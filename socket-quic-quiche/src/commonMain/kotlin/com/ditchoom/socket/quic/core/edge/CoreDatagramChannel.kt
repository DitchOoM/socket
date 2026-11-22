@file:OptIn(ExperimentalDatagramApi::class, ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.NO_DATAGRAM_ROOM
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicDatagramTooLarge
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicNativeMemoryRequiredException
import com.ditchoom.socket.quic.QuicWriteTarget
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.closeReason
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.DATAGRAM_ROOM
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.Feed
import com.ditchoom.socket.quic.core.conn.Offered
import com.ditchoom.socket.quic.core.conn.RoomWait
import com.ditchoom.socket.quic.core.conn.RoomWithdraw
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.endOf
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What one datagram receive of the core came to. */
internal sealed interface DatagramReceive {
    /** A datagram arrived in [buffer], which is the caller's from here on. */
    class Received(
        val buffer: PlatformBuffer,
    ) : DatagramReceive

    /** The connection ended with [end]: no datagram will arrive. */
    class ConnectionEnded(
        val end: QuicConnectionEnd,
    ) : DatagramReceive

    /** The connection carries no datagram. */
    data object NotNegotiated : DatagramReceive

    /** Another receive of this connection is under way: its datagrams have one consumer. */
    data object ReceiverBusy : DatagramReceive
}

/**
 * A core connection's RFC 9221 datagrams. [send] never suspends and never throws for an expected outcome: it copies the
 * datagram into the connection's send queue, or answers why not, as a [DatagramSend]. [receive] takes the next datagram
 * that arrived, as a [DatagramReceive]; a connection's datagrams have one receiver at a time. The buffer-flow channel's
 * calls are kept on top of these: their refusals are the typed exceptions they always raised.
 */
@Published
internal class CoreDatagramChannel(
    private val connection: CoreConnection,
    private val conn: Conn,
    private val edge: EdgeState,
    private val factory: BufferFactory,
    override val peer: SocketAddress,
) : ConnectedDatagramChannel {
    private val receiving = AtomicInt(FREE)
    private val full = Full()

    override val isOpen: Boolean get() = edge.end() is EdgeEnd.Open

    override val localAddress: LocalAddress = LocalAddress.Unknown

    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(requiresNativeMemoryBuffers = QuicheDriver.capabilities.requiresNativeMemoryBuffers)

    override val maxWritableSize: Int get() = conn.datagramMaxBytes()

    /** Queues [payload]'s remaining bytes as one datagram; [payload] is the caller's again when this returns. */
    fun send(payload: ReadBuffer): DatagramSend = send(0L, 0, payload)

    /** Queues [prefix]'s remaining bytes then [payload]'s as one datagram, copied once into the queue and joined there. */
    fun send(
        prefix: ReadBuffer,
        payload: ReadBuffer,
    ): DatagramSend {
        val length = prefix.remaining()
        return send(if (length == 0) 0L else addressOf(prefix), length, payload)
    }

    private fun send(
        prefix: Long,
        prefixLength: Int,
        payload: ReadBuffer,
    ): DatagramSend {
        val out =
            when (val datagrams = conn.datagrams()) {
                is Datagrams.Off -> return DatagramSend.NotNegotiated
                is Datagrams.On -> datagrams.out
            }
        when (val life = conn.life()) {
            is Life.Running -> Unit
            is Life.Closing -> return DatagramSend.ConnectionEnded(publicEndOf(life.end))
            is Life.Over -> return DatagramSend.ConnectionEnded(publicEndOf(endOf(life)))
        }
        val room = out.room()
        if (room == NO_DATAGRAM_ROOM) return DatagramSend.NotNegotiated
        val length = payload.remaining()
        if (prefixLength + length > room) return DatagramSend.TooLarge(room)
        return when (out.queue.offer(prefix, prefixLength, if (length == 0) 0L else addressOf(payload), length)) {
            Offered.Queued -> {
                if (out.queue.wantsFeed() == Feed.Hand) conn.submit(out.queue.feedWork, CallerPlace.HandToLoop)
                DatagramSend.Sent
            }
            Offered.Full -> {
                conn.counts.datagramRefusedQueueFull()
                full
            }
            Offered.Closed -> endedNow()
            Offered.Unaddressable -> raise(Raised.FactoryNotNative(factory.allocate(1)), QuicCloseCircumstance.Ended)
        }
    }

    /** The send queue closed: the connection's life is over by then, so it says how the connection ended. */
    private fun endedNow(): DatagramSend = DatagramSend.ConnectionEnded(publicEndOf(endedWith()))

    /**
     * How the connection ended, for a send refused for its end: the connection's life records the end before a send is
     * refused for it, so a life still [Life.Running] here is a send that raced the end's publication, read as released.
     */
    private fun endedWith(): ConnEnd =
        when (val life = conn.life()) {
            is Life.Closing -> life.end
            is Life.Over -> endOf(life)
            is Life.Running -> ConnEnd.Released
        }

    private fun addressOf(buffer: ReadBuffer): Long =
        NativeBytes.addressing(buffer, { it + buffer.position() }) {
            raise(Raised.Thrown(notNative(buffer)), QuicCloseCircumstance.Ended)
        }

    private fun notNative(buffer: ReadBuffer) =
        QuicNativeMemoryRequiredException.forBuffer(QuicWriteTarget.Datagram, buffer, QuicheDriver.capabilities)

    /** Suspends until the send queue is at its low-water mark or the connection ended; allocates only to wait. */
    suspend fun awaitRoom() {
        val out =
            when (val datagrams = conn.datagrams()) {
                is Datagrams.Off -> return
                is Datagrams.On -> datagrams.out
            }
        if (out.queue.size() <= out.queue.lowWater || conn.life() !is Life.Running) return
        val place = connection.placeOf(currentCoroutineContext())
        suspendCancellableCoroutine { caller ->
            val wait = RoomWait(caller)
            conn.submit(wait, place)
            caller.invokeOnCancellation { conn.submit(RoomWithdraw(wait), CallerPlace.HandToLoop) }
        }
    }

    /** This channel's one [DatagramSend.QueueFull]: telling a sender the queue is full allocates nothing. */
    private inner class Full : DatagramSend.QueueFull() {
        override suspend fun awaitRoom() = this@CoreDatagramChannel.awaitRoom()
    }

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        when (val sent = send(payload)) {
            is DatagramSend.Sent -> Unit
            is DatagramSend.QueueFull -> refused(QuicDatagramRefused(DatagramRefusal.QueueFull))
            is DatagramSend.TooLarge -> refused(QuicDatagramTooLarge(payload.remaining(), sent.maxBytes))
            is DatagramSend.NotNegotiated -> refused(QuicDatagramRefused(DatagramRefusal.NotNegotiated))
            is DatagramSend.ConnectionEnded -> raise(Raised.ConnEnded(endedWith()), QuicCloseCircumstance.Ended)
        }
    }

    private fun refused(why: Exception): Nothing = raise(Raised.Thrown(why), QuicCloseCircumstance.Ended)

    /** The next datagram, or why none comes; a second receive while one is under way is [DatagramReceive.ReceiverBusy]. */
    suspend fun receiveDatagram(): DatagramReceive {
        when (conn.datagrams()) {
            is Datagrams.Off -> return DatagramReceive.NotNegotiated
            is Datagrams.On -> Unit
        }
        if (!receiving.compareAndSet(FREE, TAKEN)) return DatagramReceive.ReceiverBusy
        try {
            return received()
        } finally {
            receiving.store(FREE)
        }
    }

    private suspend fun received(): DatagramReceive {
        val context = currentCoroutineContext()
        val buffer = factory.allocate(DATAGRAM_ROOM)
        buffer.position(0)
        buffer.setLimit(DATAGRAM_ROOM)
        val address =
            when (val at = NativeBytes.addressOf(buffer)) {
                is ReadAddress.At -> at.address
                is ReadAddress.NotAddressable -> {
                    buffer.freeNativeMemory()
                    raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.Ended)
                }
            }
        val op = DatagramReceiveOp(ReceiveAnswer.Arrived(buffer), address, DATAGRAM_ROOM, conn, context)
        conn.submit(op, connection.placeOf(context))
        return when (val answer = op.answer()) {
            is ReceiveAnswer.Arrived -> DatagramReceive.Received(answer.buffer)
            is ReceiveAnswer.Ended -> DatagramReceive.ConnectionEnded(publicEndOf(answer.end))
            is ReceiveAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is ReceiveAnswer.Cancelled -> raiseCancelled(context)
        }
    }

    override suspend fun receive(): DatagramReadResult =
        when (val received = receiveDatagram()) {
            is DatagramReceive.Received ->
                DatagramReadResult.Received(Datagram(received.buffer, peer, Ecn.Unknown, LocalAddress.Unknown, HopLimit.Unknown))
            is DatagramReceive.ConnectionEnded -> DatagramReadResult.Closed(closedBy(received.end))
            is DatagramReceive.NotNegotiated -> refused(QuicDatagramRefused(DatagramRefusal.NotNegotiated))
            is DatagramReceive.ReceiverBusy -> refused(QuicDatagramRefused(DatagramRefusal.ReceiverBusy))
        }

    /** How [end] reads as the error a closed datagram flow reports: a close with no error of its own is [QuicError.NoError]. */
    private fun closedBy(end: QuicConnectionEnd): QuicError =
        when (val reason = end.closeReason) {
            is QuicCloseReason.ByPeer -> reason.error
            is QuicCloseReason.ByLocal -> reason.error
            is QuicCloseReason.Graceful, is QuicCloseReason.Unspecified -> QuicError.NoError
        }

    /** The connection owns the datagram flow's lifecycle; closing the channel alone does nothing. */
    override fun close() = Unit

    private companion object {
        const val FREE = 0
        const val TAKEN = 1
    }
}
