package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.CinteropNetShim
import com.ditchoom.socket.quic.NetBinding
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The native stack's io on Kotlin/Native through the QNet cinterop: the same sockets, batching and records the JVM
 * reaches through FFM or JNI, so the K/N core loop can stand on them.
 */
class CinteropNativeIoTests {
    @Test
    fun aBatchArrivesAsItsDatagramsThroughTheCinteropBinding() {
        assertEquals(NetBinding.Cinterop, CinteropNetShim.binding)
        val loopback = PathKey(4, 0, 0L, 0x7f000001L)
        val server = open(loopback, SocketPeer.Unconnected)
        val client = open(loopback, SocketPeer.Connected(server.second))
        val batch = SendBatch(native(BATCH * DATAGRAM), BatchLimits(BATCH, DATAGRAM))
        val sizes = listOf(1200, 1200, 700, 1200)
        sizes.forEach { batch.commit(it, SendTarget.ConnectedPeer) }
        assertEquals(SendBatchResult.AllTaken, client.first.send(batch))
        val buffers = List(BATCH) { ReceiveBuffer(it, native(DATAGRAM)) }
        val receive = ReceiveBatch(FixedPool(BATCH) { buffers[it] }, BatchLimits(BATCH, DATAGRAM))
        val lengths = ArrayList<Int>()
        repeat(5_000) {
            if (lengths.size < sizes.size && server.first.receive(receive) is ReceiveBatchResult.Got) {
                for (i in 0 until receive.count) lengths.add(receive.lengthAt(i))
            }
            receive.release()
        }
        assertEquals(sizes, lengths)
        client.first.close()
        server.first.close()
    }

    private fun open(
        bind: PathKey,
        peer: SocketPeer,
    ): Pair<NativeIo, PathKey> {
        val socket =
            assertIs<NativeOpened.Opened>(
                NativeSocket.open(CinteropNetShim, native(NativeSocket.SPEC_BYTES), bind, peer, 1 shl 20, 1 shl 20, UdpBatching.Newest, BATCH),
            ).socket
        val io = NativeIo(CinteropNetShim, socket, listOf(native(NativeIo.SEND_RECORD * BATCH)), native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE))
        return io to socket.local
    }

    private fun native(size: Int): NativeBytes = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(size))).bytes

    private companion object {
        const val BATCH = 8
        const val DATAGRAM = 1500
    }
}
