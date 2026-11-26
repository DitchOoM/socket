package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly

/**
 * The [DatagramBatchIo] of the native stack: one `qnet` call per batch, whatever the batching. Sending is [NativeSend]
 * (segmented groups, the EMSGSIZE fallback), receiving is [NativeReceive] (per datagram or coalesced), and the socket's
 * own lifecycle (reopen, a second socket beside it, close) is [NativeSocket]'s; this class puts the three behind one interface and shares
 * their batching mode.
 */
@LoopOnly
internal class NativeIo(
    private val shim: NetShim,
    private val socket: NativeSocket,
    sendRecords: List<NativeBytes>,
    /** The loop's receive records, [RECV_RECORD] bytes per datagram a receive may take. */
    receiveRecords: NativeBytes,
    /** The peers whose keys the socket keeps: one for a client's, many for a server's. */
    peers: PeerKeys = PeerKeys(PeerKeys.CLIENT_SETS),
    /** The batching the socket starts at: what its probe found. */
    first: BatchCapability = socket.capability,
) : DatagramBatchIo {
    private val mode = CapabilityCell(first)
    private val sending = NativeSend(shim, socket, sendRecords, mode)
    private val receiving = NativeReceive(shim, socket, receiveRecords, mode, peers)

    override val capability: BatchCapability get() = mode.load()

    /** The descriptor the poller watches; the loop reads it again after [reopen]. */
    fun descriptor(): Int = socket.cell.descriptor()

    /** The address and port the socket is bound to now. */
    fun local(): PathKey = socket.local

    override fun send(batch: SendBatch): SendBatchResult = sending.send(batch)

    override fun receive(into: ReceiveBatch): ReceiveBatchResult = receiving.receive(into, ReceiveOn.Carrying)

    override fun receiveSecond(into: ReceiveBatch): ReceiveBatchResult =
        when (val pair = socket.pair()) {
            is NativePair.One -> ReceiveBatchResult.Nothing
            is NativePair.Two ->
                when (val got = receiving.receive(into, pair.second)) {
                    is ReceiveBatchResult.Failed -> ReceiveBatchResult.Nothing
                    is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog, is ReceiveBatchResult.Nothing -> got
                }
        }

    override fun probe(): SocketProbe =
        socket.cell.use(
            call = { fd -> if (shim.udpError(fd) < 0) SocketProbe.Closed else SocketProbe.Open },
            replacing = { SocketProbe.Open },
            closed = { SocketProbe.Closed },
        )

    override fun reopen(): Reopened = socket.reopen()

    override fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside = socket.openBeside(bind, ticket)

    override fun reopenSecond(): Reopened = socket.reopenSecond()

    override fun promote() = socket.promote()

    override fun closeSecond() = socket.closeSecond()

    /** The descriptor of the second socket, which the poller watches while one is open. */
    fun secondDescriptor(): Int = socket.secondDescriptor()

    override fun close(): SocketClosed {
        socket.closeSecond()
        socket.cell.close { fd -> shim.close(fd) }
        return SocketClosed
    }

    companion object {
        /** Bytes of one send record and one receive record, and where each holds its local address, as `qnet.h` lays them out. */
        const val SEND_RECORD = 56
        const val SEND_SOURCE = 32
        const val RECV_RECORD = 64
        const val RECV_DEST = 40

        /** The most datagrams one receive takes. */
        const val MAX_RECEIVE = 64
    }
}
