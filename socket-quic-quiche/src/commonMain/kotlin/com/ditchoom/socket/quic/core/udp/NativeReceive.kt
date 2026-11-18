package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly

/**
 * The receive half of [NativeIo], the loop's alone: it hands `qnet` the addresses of the pool buffers it took, and `qnet`
 * answers how many datagrams came, exactly, and what stopped it. A socket with `UDP_GRO` on ([Receiving.Coalesced]) reads one
 * `recvmmsg` per call and `qnet` splits each message into the batch's slots, so what is handed on is the datagrams as the
 * sender sent them; whole messages that did not fit wait inside `qnet` and the result says so ([ReceiveBatchResult.GotWithBacklog]).
 */
@LoopOnly
internal class NativeReceive(
    private val shim: NetShim,
    private val socket: NativeSocket,
    /** The loop's receive records, [NativeIo.RECV_RECORD] bytes per datagram a receive may take. */
    private val receiveRecords: NativeBytes,
    private val mode: CapabilityCell,
) {
    private val held = ArrayList<ReceiveBuffer>(NativeIo.MAX_RECEIVE)
    private var lastFrom: Sender = Sender.None
    private var lastAt: ArrivedOn = ArrivedOn.Unreported

    fun receive(into: ReceiveBatch): ReceiveBatchResult {
        held.clear()
        while (held.size < NativeIo.MAX_RECEIVE) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            receiveRecords.putLong(held.size * NativeIo.RECV_RECORD, buffer.bytes.address)
            held.add(buffer)
        }
        if (held.isEmpty()) return ReceiveBatchResult.Nothing
        val count = held.size
        val result =
            socket.cell.use(
                call = { fd -> readInto(fd, count, into.limits.maxDatagramSize) },
                replacing = { 0L },
                closed = { NativeResult.CLOSED },
            )
        val got = NativeResult.count(result)
        for (i in 0 until got) {
            val at = i * NativeIo.RECV_RECORD
            into.received(held[i], receiveRecords.intAt(at + LENGTH), keyAt(at + SOURCE), arrivedAt(at + NativeIo.RECV_DEST))
        }
        for (i in got until held.size) into.giveBack(held[i])
        held.clear()
        val code = NativeResult.code(result)
        return when {
            code != 0 -> ReceiveBatchResult.Failed(NativeResult.errorOf(code))
            got > 0 && result and NativeResult.BACKLOG != 0L -> ReceiveBatchResult.GotWithBacklog
            got > 0 -> ReceiveBatchResult.Got
            else -> ReceiveBatchResult.Nothing
        }
    }

    /** One `qnet` receive into the first [count] records: a coalescing socket's read is split into datagrams there. */
    private fun readInto(
        fd: Int,
        count: Int,
        size: Int,
    ): Long =
        when (socket.receiving) {
            is Receiving.Coalesced -> shim.udpRecvCoalesced(fd, receiveRecords.address, count, size)
            is Receiving.PerDatagram -> shim.udpRecv(fd, modeOf(mode.load()), receiveRecords.address, count, size)
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

    /**
     * The local address the datagram was sent to, on the socket's port, when `qnet` reports it (a wildcard server's
     * socket does); built once per run of datagrams to one address.
     */
    private fun arrivedAt(offset: Int): ArrivedOn {
        val family = receiveRecords.intAt(offset)
        if (family == 0) return ArrivedOn.Unreported
        val port = socket.local.port
        val hi = receiveRecords.longAt(offset + 8)
        val lo = receiveRecords.longAt(offset + 16)
        when (val last = lastAt) {
            is ArrivedOn.At ->
                if (last.local.family == family && last.local.port == port && last.local.hi == hi && last.local.lo == lo) {
                    return last
                }
            is ArrivedOn.Unreported -> Unit
        }
        return ArrivedOn.At(PathKey(family, port, hi, lo)).also { lastAt = it }
    }

    private sealed interface Sender {
        data object None : Sender

        class Seen(
            val key: PathKey,
        ) : Sender
    }

    private companion object {
        const val LENGTH = 8
        const val SOURCE = 16
    }
}
