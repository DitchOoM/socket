@file:OptIn(ExperimentalDatagramApi::class, ExperimentalAtomicApi::class, QuicEngineSpi::class)

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
import com.ditchoom.socket.quic.DatagramRoomWait
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicDatagramTooLarge
import com.ditchoom.socket.quic.QuicEngineSpi
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.closeReason
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.DATAGRAM_ROOM
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.EndWork
import com.ditchoom.socket.quic.core.conn.Feed
import com.ditchoom.socket.quic.core.conn.Negotiation
import com.ditchoom.socket.quic.core.conn.Offered
import com.ditchoom.socket.quic.core.conn.QueueLife
import com.ditchoom.socket.quic.core.conn.RoomWait
import com.ditchoom.socket.quic.core.conn.RoomWithdraw
import com.ditchoom.socket.quic.core.conn.maxBytes
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.endOf
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.quicheCapabilities
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What one datagram receive of the core came to, grouped as a send's is. */
internal sealed interface DatagramReceive {
    /** A datagram arrived in [buffer], which is the caller's from here on. */
    class Received(
        val buffer: PlatformBuffer,
    ) : DatagramReceive

    /** No datagram was received. */
    sealed interface NotReceived : DatagramReceive {
        /** Another receive of this connection is under way: its datagrams have one receiver at a time. */
        data object ReceiverBusy : NotReceived

        /** No datagram will arrive: stop receiving. */
        sealed interface Unavailable : NotReceived {
            data object NotNegotiated : Unavailable

            /** The connection ended with [end]. */
            class ConnectionEnded(
                val end: QuicConnectionEnd,
            ) : Unavailable
        }
    }
}

/**
 * A core connection's RFC 9221 datagrams. [send] never suspends and never throws: it copies the datagram into the
 * connection's send queue, from native memory by address and from any other memory through the buffer, or answers why
 * not, as a [DatagramSend]. [receiveDatagram] takes the next datagram that arrived; a connection's datagrams have one
 * receiver at a time. The buffer-flow channel's calls are kept on top of these, with the typed exceptions they raised.
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
    private val full = DatagramSend.NotSent.QueueFull.of(DatagramRoomWait { awaitRoom() })

    override val isOpen: Boolean get() = edge.end() is EdgeEnd.Open

    override val localAddress: LocalAddress = LocalAddress.Unknown

    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(requiresNativeMemoryBuffers = quicheCapabilities.requiresNativeMemoryBuffers)

    override val maxWritableSize: Int get() = conn.datagrams().maxBytes()

    /** Queues [payload]'s remaining bytes as one datagram; [payload] is the caller's again, unmoved, when this returns. */
    fun send(payload: ReadBuffer): DatagramSend = queue(payload, 0, payload)

    /**
     * Queues [prefix]'s remaining bytes then [payload]'s as one datagram, joined in the one copy into the queue; a
     * [DatagramSend.NotSent.TooLarge] reports the room left for [payload].
     */
    fun send(
        prefix: ReadBuffer,
        payload: ReadBuffer,
    ): DatagramSend = queue(prefix, prefix.remaining(), payload)

    private fun queue(
        prefix: ReadBuffer,
        prefixLength: Int,
        payload: ReadBuffer,
    ): DatagramSend {
        val out =
            when (val datagrams = conn.datagrams()) {
                is Datagrams.Off -> return DatagramSend.NotSent.Unavailable.NotNegotiated
                is Datagrams.On -> datagrams.out
            }
        when (val life = conn.life()) {
            is Life.Running -> Unit
            is Life.Closing -> return ended(endOf(life))
            is Life.Over -> return ended(endOf(life))
        }
        val room = out.room()
        when (room.negotiation()) {
            Negotiation.Negotiated -> Unit
            Negotiation.None -> return DatagramSend.NotSent.Unavailable.NotNegotiated
        }
        val length = payload.remaining()
        if (prefixLength + length > room.maxBytes()) return DatagramSend.NotSent.TooLarge(maxOf(room.maxBytes() - prefixLength, 0))
        return when (val offered = out.queue.offer(prefix, prefixLength, payload, length)) {
            is Offered.Queued -> {
                if (out.queue.wantsFeed() == Feed.Hand) conn.submit(out.queue.feedWork, CallerPlace.HandToLoop)
                DatagramSend.Sent
            }
            is Offered.Full -> {
                conn.counts.datagramRefusedQueueFull()
                full
            }
            is Offered.Contended -> {
                conn.counts.datagramRefusedQueueFull()
                // A place this offer let go may have brought the queue to its low-water mark: the connection wakes its waiters.
                if (out.queue.wantsFeed() == Feed.Hand) conn.submit(out.queue.feedWork, CallerPlace.HandToLoop)
                full
            }
            is QueueLife.Closed -> ended(offered.end)
            is Offered.NoMemory -> {
                // The connect checked its factory makes memory with an address; one that fails cannot carry datagrams.
                val end = ConnEnd.ProcessingFailed(Thrown(offered.failure))
                conn.submit(EndWork(end), CallerPlace.HandToLoop)
                ended(end)
            }
        }
    }

    private fun ended(end: ConnEnd): DatagramSend = DatagramSend.NotSent.Unavailable.ConnectionEnded(publicEndOf(end))

    /** Suspends until the send queue is at its low-water mark or the channel became unavailable; allocates only to wait. */
    suspend fun awaitRoom() {
        val out =
            when (val datagrams = conn.datagrams()) {
                is Datagrams.Off -> return
                is Datagrams.On -> datagrams.out
            }
        if (out.queue.size() <= out.queue.lowWater || out.queue.life() is QueueLife.Closed) return
        val place = connection.placeOf(currentCoroutineContext())
        suspendCancellableCoroutine { caller ->
            val wait = RoomWait(caller)
            conn.submit(wait, place)
            caller.invokeOnCancellation { conn.submit(RoomWithdraw(wait), CallerPlace.HandToLoop) }
        }
    }

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        when (val sent = send(payload)) {
            is DatagramSend.Sent -> Unit
            is DatagramSend.NotSent.QueueFull -> refused(QuicDatagramRefused(DatagramRefusal.QueueFull))
            is DatagramSend.NotSent.TooLarge -> refused(QuicDatagramTooLarge(payload.remaining(), sent.maxBytes))
            is DatagramSend.NotSent.Unavailable.NotNegotiated -> refused(QuicDatagramRefused(DatagramRefusal.NotNegotiated))
            is DatagramSend.NotSent.Unavailable.ConnectionEnded ->
                when (val life = conn.life()) {
                    // Past the close point nothing more is sent, datagrams included.
                    is Life.Closing -> raise(Raised.PastClose(endOf(life)), QuicCloseCircumstance.Ended)
                    is Life.Running, is Life.Over -> raiseEnded(sent.end, QuicCloseCircumstance.Ended)
                }
        }
    }

    private fun refused(why: Exception): Nothing = raise(Raised.Thrown(why), QuicCloseCircumstance.Ended)

    /** The next datagram, or why none comes; a second receive while one is under way is [DatagramReceive.NotReceived.ReceiverBusy]. */
    suspend fun receiveDatagram(): DatagramReceive {
        when (conn.datagrams()) {
            is Datagrams.Off -> return DatagramReceive.NotReceived.Unavailable.NotNegotiated
            is Datagrams.On -> Unit
        }
        if (!receiving.compareAndSet(FREE, TAKEN)) return DatagramReceive.NotReceived.ReceiverBusy
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
            is ReceiveAnswer.Ended -> DatagramReceive.NotReceived.Unavailable.ConnectionEnded(publicEndOf(answer.end))
            is ReceiveAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is ReceiveAnswer.Cancelled -> raiseCancelled(context)
        }
    }

    override suspend fun receive(): DatagramReadResult =
        when (val received = receiveDatagram()) {
            is DatagramReceive.Received ->
                DatagramReadResult.Received(Datagram(received.buffer, peer, Ecn.Unknown, LocalAddress.Unknown, HopLimit.Unknown))
            is DatagramReceive.NotReceived.Unavailable.ConnectionEnded -> DatagramReadResult.Closed(closedBy(received.end))
            is DatagramReceive.NotReceived.Unavailable.NotNegotiated -> refused(QuicDatagramRefused(DatagramRefusal.NotNegotiated))
            is DatagramReceive.NotReceived.ReceiverBusy -> refused(QuicDatagramRefused(DatagramRefusal.ReceiverBusy))
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
