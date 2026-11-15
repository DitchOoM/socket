package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.loop.Collected
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

private class Reply(
    val buffer: PlatformBuffer,
    val address: Long,
) {
    var length = 0
    var target: SendTarget = SendTarget.ConnectedPeer
}

/**
 * Version Negotiation and Retry packets, which answer a datagram that has no connection. Each is written by quiche into
 * a slot of a fixed ring when the datagram arrives and copied into the loop's send batch in the pass's collecting.
 * A full ring drops the reply: the client retransmits. Loop thread only.
 */
internal class StatelessReplies(
    factory: BufferFactory,
) {
    private val ring =
        Array(CAPACITY) {
            val buffer = factory.allocate(MAX_REPLY)
            Reply(buffer, addressOf(buffer))
        }
    private var first = 0
    private var queued = 0

    /** Queues a Version Negotiation for the unsupported-version Initial at [datagram], read into [header]. */
    fun negotiateVersion(
        api: QuicheApi,
        header: PacketHeader,
        datagram: ReceiveBuffer,
        from: PathKey,
        counts: ServerCounts,
        local: PathKey,
    ) {
        if (queued == CAPACITY) {
            counts.droppedReplies++
            return
        }
        val reply = ring[(first + queued) % CAPACITY]
        // The reply's destination id is the packet's source id and its source id the packet's destination id.
        val written =
            api.negotiateVersion(
                datagram.address + header.scidOffset,
                header.scidLength,
                datagram.address + header.dcidOffset,
                header.dcidLength,
                reply.address,
                MAX_REPLY,
            )
        if (written <= 0) {
            counts.droppedReplies++
            return
        }
        enqueue(reply, written, from, local)
        counts.versionNegotiationsSent++
    }

    /** Queues a Retry for the version 1 Initial at [datagram], naming [newScid] and carrying the token at [token]. */
    fun retry(
        api: QuicheApi,
        header: PacketHeader,
        datagram: ReceiveBuffer,
        from: PathKey,
        newScid: Long,
        token: Long,
        tokenLength: Int,
        counts: ServerCounts,
        local: PathKey,
    ) {
        if (queued == CAPACITY) {
            counts.droppedReplies++
            return
        }
        val reply = ring[(first + queued) % CAPACITY]
        val written =
            api.retry(
                datagram.address + header.scidOffset,
                header.scidLength,
                datagram.address + header.dcidOffset,
                header.dcidLength,
                newScid,
                QUIC_ISSUED,
                token,
                tokenLength,
                QUIC_V1,
                reply.address,
                MAX_REPLY,
            )
        if (written <= 0) {
            counts.droppedReplies++
            return
        }
        enqueue(reply, written, from, local)
        counts.retriesSent++
    }

    private fun enqueue(
        reply: Reply,
        length: Int,
        from: PathKey,
        local: PathKey,
    ) {
        reply.length = length
        reply.target = SendTarget.ServerReply(to = from, from = ReplySource.Recorded(local))
        queued++
    }

    /** Copies the queued replies into [out] while it has room. [Collected.MoreWaiting] when some are left. */
    fun writeInto(out: SendBatch): Collected {
        while (queued > 0 && out.room() === Room.Open) {
            val reply = ring[first]
            val storage = out.storage
            storage.setLimit(storage.capacity)
            storage.position(out.nextOffset())
            reply.buffer.position(0)
            reply.buffer.setLimit(reply.length)
            storage.write(reply.buffer)
            out.commit(reply.length, reply.target)
            first = (first + 1) % CAPACITY
            queued--
        }
        return if (queued > 0) Collected.MoreWaiting else Collected.Everything
    }

    fun free() {
        for (reply in ring) reply.buffer.freeNativeMemory()
    }

    private companion object {
        const val CAPACITY = 64
        const val MAX_REPLY = 1500
        const val QUIC_ISSUED = 16
        const val QUIC_V1 = 1
    }
}
