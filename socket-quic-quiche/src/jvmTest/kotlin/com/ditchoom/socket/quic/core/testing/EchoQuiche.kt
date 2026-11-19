package com.ditchoom.socket.quic.core.testing

import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi

/**
 * A quiche that answers every datagram it is handed with the same bytes, in order: the loop's echo, with the
 * connection, its turn and its socket in the path. Copies through preallocated native slots, so it allocates nothing.
 */
internal class EchoQuiche(
    val stub: StubQuicheApi = StubQuicheApi(),
    capacity: Int = 2048,
    private val slotBytes: Int = 1500,
) : QuicheApi by stub {
    private val slots = nativeBytes(capacity * slotBytes)
    private val lengths = IntArray(capacity)
    private var head = 0
    private var tail = 0
    private var held = 0

    /** Datagrams quiche was handed. */
    var received = 0L
        private set

    override fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int {
        received++
        if (held == lengths.size) return bufLen
        UnsafeMemory.copyMemory(buf, slots.address + tail.toLong() * slotBytes, bufLen.toLong())
        lengths[tail] = bufLen
        tail = (tail + 1) % lengths.size
        held++
        return bufLen
    }

    override fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int {
        if (held == 0) return DONE
        val length = lengths[head]
        UnsafeMemory.copyMemory(slots.address + head.toLong() * slotBytes, buf, length.toLong())
        head = (head + 1) % lengths.size
        held--
        return length
    }

    private companion object {
        const val DONE = -1
    }
}
