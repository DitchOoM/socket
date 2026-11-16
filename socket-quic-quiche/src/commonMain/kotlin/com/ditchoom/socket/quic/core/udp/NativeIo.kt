package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.core.observe.LoopOnly

/**
 * The [DatagramBatchIo] of the native stack: one `qnet` call per batch, whatever the batching. A send writes the
 * batch's lengths and targets into its lane's native records ([sendRecords], one per lane by [SendBatch.lane]) and
 * hands `qnet` the batch's own memory; a receive hands `qnet` the addresses of the pool buffers it took. `qnet`
 * answers how many datagrams went or came, exactly, and what stopped it.
 *
 * Receiving is the loop's alone; sending is safe from any thread, one thread per lane. A refused segmented send
 * steps the socket down to [BatchCapability.Multi] for good.
 */
@LoopOnly
internal class NativeIo(
    private val shim: NetShim,
    private val socket: NativeSocket,
    private val sendRecords: List<NativeBytes>,
    /** The loop's receive records, [RECV_RECORD] bytes per datagram a receive may take. */
    private val receiveRecords: NativeBytes,
) : DatagramBatchIo {
    private val mode = CapabilityCell(socket.capability)
    private val held = ArrayList<ReceiveBuffer>(MAX_RECEIVE)
    private var lastFrom: Sender = Sender.None

    override val capability: BatchCapability get() = mode.load()

    /** The descriptor the poller watches; the loop reads it again after [reopen]. */
    fun descriptor(): Int = socket.cell.descriptor()

    /** The address and port the socket is bound to now. */
    fun local(): PathKey = socket.local

    override fun send(batch: SendBatch): SendBatchResult {
        val count = batch.pending
        val records = sendRecords[batch.lane]
        for (n in 0 until count) {
            val at = n * SEND_RECORD
            records.putInt(at, batch.lengthOf(n))
            when (val target = batch.targetOf(n)) {
                is SendTarget.ConnectedPeer -> records.putInt(at + TARGET, 0)
                is SendTarget.ServerReply -> putRecord(records, at + TARGET, target.to)
            }
        }
        val capability = mode.load()
        val result =
            socket.cell.use(
                call = { fd ->
                    shim.udpSend(
                        fd,
                        socket.family,
                        modeOf(capability),
                        batch.storage.address + batch.offsetOf(0),
                        batch.limits.maxDatagramSize,
                        records.address,
                        count,
                        segmentsOf(capability),
                    )
                },
                replacing = { REPLACED },
                closed = { CLOSED },
            )
        if (result and SEGMENT_REFUSED != 0L) mode.stepDown(capability)
        val taken = (result and COUNT_MASK).toInt()
        val code = ((result ushr CODE_SHIFT) and CODE_MASK).toInt()
        return if (code == 0) SendBatchResult.AllTaken else SendBatchResult.Partial(taken, errorOf(code))
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        held.clear()
        while (held.size < MAX_RECEIVE) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            receiveRecords.putLong(held.size * RECV_RECORD, buffer.bytes.address)
            held.add(buffer)
        }
        if (held.isEmpty()) return ReceiveBatchResult.Nothing
        val count = held.size
        val result =
            socket.cell.use(
                call = { fd -> shim.udpRecv(fd, modeOf(mode.load()), receiveRecords.address, count, into.limits.maxDatagramSize) },
                replacing = { 0L },
                closed = { CLOSED },
            )
        val got = (result and COUNT_MASK).toInt()
        for (i in 0 until got) {
            val at = i * RECV_RECORD
            into.received(held[i], receiveRecords.intAt(at + LENGTH), keyAt(at + SOURCE), ArrivedOn.Unreported)
        }
        for (i in got until held.size) into.giveBack(held[i])
        held.clear()
        val code = ((result ushr CODE_SHIFT) and CODE_MASK).toInt()
        return when {
            code != 0 -> ReceiveBatchResult.Failed(errorOf(code))
            got > 0 -> ReceiveBatchResult.Got
            else -> ReceiveBatchResult.Nothing
        }
    }

    override fun probe(): SocketProbe =
        socket.cell.use(
            call = { fd -> if (shim.udpError(fd) < 0) SocketProbe.Closed else SocketProbe.Open },
            replacing = { SocketProbe.Open },
            closed = { SocketProbe.Closed },
        )

    override fun reopen(): Reopened = socket.reopen()

    override fun close(): SocketClosed {
        socket.cell.close { fd -> shim.close(fd) }
        return SocketClosed
    }

    private fun errorOf(code: Int): UdpError =
        when (code) {
            REPLACED_CODE -> UdpError.Replaced
            CLOSED_CODE -> UdpError.SocketClosed
            else -> udpErrorOf(SocketRefusal.of(code))
        }

    /** One peer usually sends a run of datagrams: its key is built once per run. */
    private fun keyAt(offset: Int): PathKey {
        val family = receiveRecords.intAt(offset)
        val port = receiveRecords.intAt(offset + 4)
        val hi = receiveRecords.longAt(offset + 8)
        val lo = receiveRecords.longAt(offset + 16)
        when (val last = lastFrom) {
            is Sender.Seen ->
                if (last.key.family == family &&
                    last.key.port == port &&
                    last.key.hi == hi &&
                    last.key.lo == lo
                ) {
                    return last.key
                }
            is Sender.None -> Unit
        }
        val key = PathKey(family, port, hi, lo)
        lastFrom = Sender.Seen(key)
        return key
    }

    private sealed interface Sender {
        data object None : Sender

        class Seen(
            val key: PathKey,
        ) : Sender
    }

    companion object {
        /** Bytes of one send record and one receive record, as `qnet.h` lays them out. */
        const val SEND_RECORD = 32
        const val RECV_RECORD = 40

        /** The most datagrams one receive takes. */
        const val MAX_RECEIVE = 64
        private const val TARGET = 8
        private const val LENGTH = 8
        private const val SOURCE = 16
        private const val COUNT_MASK = 0xFFFF_FFFFL
        private const val CODE_SHIFT = 32
        private const val CODE_MASK = 0xFFFFL
        private const val SEGMENT_REFUSED = 1L shl 48
        private const val REPLACED_CODE = 0xFFFE
        private const val CLOSED_CODE = 0xFFFD
        private const val REPLACED = REPLACED_CODE.toLong() shl CODE_SHIFT
        private const val CLOSED = CLOSED_CODE.toLong() shl CODE_SHIFT
    }
}

private fun modeOf(capability: BatchCapability): Int =
    when (capability) {
        is BatchCapability.OneByOne -> 0
        is BatchCapability.Multi -> 1
        is BatchCapability.Segmented ->
            when (capability.send) {
                is Gso.On -> 2
                is Gso.Off -> 1
            }
    }

private fun segmentsOf(capability: BatchCapability): Int =
    when (capability) {
        is BatchCapability.OneByOne, is BatchCapability.Multi -> 1
        is BatchCapability.Segmented ->
            when (val gso = capability.send) {
                is Gso.On -> gso.maxSegments
                is Gso.Off -> 1
            }
    }
