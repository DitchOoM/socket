package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly

/**
 * The [DatagramBatchIo] of the native stack: one `qnet` call per batch, whatever the batching. Sending is [NativeSend]
 * (segmented groups, the EMSGSIZE fallback), receiving is [NativeReceive] (per datagram or coalesced), and the socket's
 * own lifecycle (reopen, move, close) is [NativeSocket]'s; this class puts the three behind one interface and shares
 * their batching mode.
 */
@LoopOnly
internal class NativeIo(
    private val shim: NetShim,
    private val socket: NativeSocket,
    sendRecords: List<NativeBytes>,
    /** The loop's receive records, [RECV_RECORD] bytes per datagram a receive may take. */
    receiveRecords: NativeBytes,
    /** The batching the socket starts at: what its probe found. */
    first: BatchCapability = socket.capability,
) : DatagramBatchIo {
    private val mode = CapabilityCell(first)
    private val sending = NativeSend(shim, socket, sendRecords, mode)
    private val receiving = NativeReceive(shim, socket, receiveRecords, mode)

    override val capability: BatchCapability get() = mode.load()

    /** The descriptor the poller watches; the loop reads it again after [reopen]. */
    fun descriptor(): Int = socket.cell.descriptor()

    /** The address and port the socket is bound to now. */
    fun local(): PathKey = socket.local

    override fun send(batch: SendBatch): SendBatchResult = sending.send(batch)

    override fun receive(into: ReceiveBatch): ReceiveBatchResult = receiving.receive(into)

    override fun probe(): SocketProbe =
        socket.cell.use(
            call = { fd -> if (shim.udpError(fd) < 0) SocketProbe.Closed else SocketProbe.Open },
            replacing = { SocketProbe.Open },
            closed = { SocketProbe.Closed },
        )

    override fun reopen(): Reopened = socket.reopen()

    override fun moveTo(bind: PathKey): Moved = socket.moveTo(bind)

    override fun close(): SocketClosed {
        socket.cell.close { fd -> shim.close(fd) }
        return SocketClosed
    }

    companion object {
        /** Bytes of one send record and one receive record, as `qnet.h` lays them out. */
        const val SEND_RECORD = 32
        const val RECV_RECORD = 40

        /** The most datagrams one receive takes. */
        const val MAX_RECEIVE = 64
    }
}
