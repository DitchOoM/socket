@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
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
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicDatagramTooLarge
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicNativeMemoryRequiredException
import com.ditchoom.socket.quic.QuicWriteTarget
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.DATAGRAM_ROOM
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.quicheCapabilities
import kotlinx.coroutines.currentCoroutineContext

/**
 * A core connection's RFC 9221 datagrams as a buffer-flow channel. A send is whole or refused, and a refusal is typed:
 * [QuicDatagramTooLarge] when the datagram does not fit the path, [QuicDatagramRefused] when datagrams are not
 * negotiated or quiche's queue is full. A receive suspends until a datagram arrives or the connection ends. Calls hand
 * their work to the connection like every other call of the edge, so a caller on a main dispatcher makes no system call.
 */
internal class CoreDatagramChannel(
    private val connection: CoreConnection,
    private val conn: Conn,
    private val edge: EdgeState,
    private val factory: BufferFactory,
    override val peer: SocketAddress,
) : ConnectedDatagramChannel {
    override val isOpen: Boolean get() = edge.end() is EdgeEnd.Open

    override val localAddress: LocalAddress = LocalAddress.Unknown

    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(requiresNativeMemoryBuffers = quicheCapabilities.requiresNativeMemoryBuffers)

    override val maxWritableSize: Int get() = conn.datagramMaxBytes()

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        val context = currentCoroutineContext()
        val length = payload.remaining()
        val address =
            if (length == 0) {
                0L
            } else {
                when (val at = NativeBytes.addressOf(payload)) {
                    is ReadAddress.At -> at.address + payload.position()
                    is ReadAddress.NotAddressable -> {
                        val refused =
                            QuicNativeMemoryRequiredException.forBuffer(
                                QuicWriteTarget.Datagram,
                                payload,
                                quicheCapabilities,
                            )
                        raise(Raised.Thrown(refused), QuicCloseCircumstance.Ended)
                    }
                }
            }
        val op = DatagramSendOp(address, length, conn, context)
        conn.submit(op, connection.placeOf(context))
        when (val answer = op.answer()) {
            is SendAnswer.Sent -> Unit
            is SendAnswer.NotNegotiated -> refused(QuicDatagramRefused(DatagramRefusal.NotNegotiated))
            is SendAnswer.QueueFull -> refused(QuicDatagramRefused(DatagramRefusal.QueueFull))
            is SendAnswer.TooLarge -> refused(QuicDatagramTooLarge(length, answer.maxBytes))
            is SendAnswer.Refused -> refused(QuicDatagramRefused(DatagramRefusal.Quiche(answer.code)))
            is SendAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is SendAnswer.Cancelled -> raiseCancelled(context)
        }
    }

    private fun refused(why: Exception): Nothing = raise(Raised.Thrown(why), QuicCloseCircumstance.Ended)

    override suspend fun receive(): DatagramReadResult {
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
            is ReceiveAnswer.Arrived ->
                DatagramReadResult.Received(Datagram(answer.buffer, peer, Ecn.Unknown, LocalAddress.Unknown, HopLimit.Unknown))
            is ReceiveAnswer.Ended -> DatagramReadResult.Closed(closedBy(answer.end))
            is ReceiveAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is ReceiveAnswer.Cancelled -> raiseCancelled(context)
        }
    }

    /** How [end] reads as the error a closed datagram flow reports: a close with no error of its own is [QuicError.NoError]. */
    private fun closedBy(end: ConnEnd): QuicError =
        when (val reason = closeReasonOf(end)) {
            is QuicCloseReason.ByPeer -> reason.error
            is QuicCloseReason.ByLocal -> reason.error
            is QuicCloseReason.Graceful, is QuicCloseReason.Unspecified -> QuicError.NoError
        }

    /** The connection owns the datagram flow's lifecycle; closing the channel alone does nothing. */
    override fun close() = Unit
}
