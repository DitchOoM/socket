package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.network
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A link whose MTU is below the datagram size: the kernel refuses a segmented send with EMSGSIZE and sends the same
 * datagrams one message each. The socket must send the batch without segments in that very call, and must not ask
 * the kernel for segments again at a size it refused.
 */
class SegmentedSendTooBigTests {
    private val real = loadNetShim()
    private val owned = ArrayList<NativeBytes>()
    private val opened = ArrayList<NativeIo>()
    private val segmentedCalls = AtomicInteger()

    /** A kernel with a 1280-byte link: a segmented send of datagrams over [LINK_LIMIT] bytes is refused. */
    private val smallLink: NetShim =
        object : NetShim by real {
            override fun udpSend(
                fd: Int,
                family: Int,
                mode: Int,
                base: Long,
                stride: Int,
                records: Long,
                count: Int,
                segments: Int,
            ): Long {
                if (mode != SEGMENTED) return real.udpSend(fd, family, mode, base, stride, records, count, segments)
                segmentedCalls.incrementAndGet()
                val length = UnsafeMemory.getInt(records)
                return if (count > 1 && length > LINK_LIMIT) {
                    EMSGSIZE shl CODE_SHIFT
                } else {
                    real.udpSend(fd, family, 1, base, stride, records, count, 1)
                }
            }
        }

    @AfterTest
    fun freeAll() {
        opened.forEach { it.close() }
        owned.forEach { it.free() }
    }

    @Test
    fun aSegmentedBatchOverTheLinkIsSentWithoutSegmentsInTheSameCall() {
        assumeNativeStack()
        val (client, server) = pair()
        val batch = sendBatch()
        repeat(9) { batch.put(it, 1350) }
        assertEquals(SendBatchResult.AllTaken, client.send(batch))
        assertEquals(9, receiveAll(server, 9).size)
        assertEquals(1, segmentedCalls.get(), "one refused segmented call, then no more for this batch")
    }

    @Test
    fun aSizeTheKernelRefusedIsNotAskedForSegmentsAgainButASmallerOneStillIs() {
        assumeNativeStack()
        val (client, server) = pair()
        repeat(3) { round ->
            val batch = sendBatch()
            repeat(9) { batch.put(it, 1350) }
            assertEquals(SendBatchResult.AllTaken, client.send(batch), "round $round")
        }
        assertEquals(1, segmentedCalls.get(), "only the first batch paid for the refusal")
        val small = sendBatch()
        repeat(9) { small.put(it, 1200) }
        assertEquals(SendBatchResult.AllTaken, client.send(small))
        assertEquals(2, segmentedCalls.get(), "datagrams under the refused size use segments again")
        assertEquals(27 + 9, receiveAll(server, 36).size)
        assertIs<BatchCapability.Segmented>(client.capability, "one refusal does not step the socket down")
    }

    private fun pair(): Pair<NativeIo, NativeIo> {
        val server = open(SocketPeer.Unconnected)
        val client = open(SocketPeer.Connected(server.local()))
        return client to server
    }

    private fun open(peer: SocketPeer): NativeIo {
        val socket =
            assertIs<NativeOpened.Opened>(
                NativeSocket.open(
                    smallLink,
                    native(NativeSocket.SPEC_BYTES),
                    InetSocketAddress("127.0.0.1", 0).toPathKey(),
                    peer,
                    1 shl 20,
                    1 shl 20,
                    UdpBatching.WithoutSegments,
                    BATCH,
                ),
            ).socket
        val io =
            NativeIo(
                smallLink,
                socket,
                listOf(native(NativeIo.SEND_RECORD * BATCH)),
                native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE),
                BatchCapability.Segmented(BATCH, Gso.On(BATCH), Gro.Off),
            )
        opened.add(io)
        return io
    }

    private fun sendBatch(): SendBatch = SendBatch(native(BATCH * DATAGRAM), BatchLimits(BATCH, DATAGRAM))

    private fun SendBatch.put(
        index: Int,
        length: Int,
    ) {
        val at = nextOffset()
        for (b in 0 until length step 8) storage.putLong(at + minOf(b, length - 8), index * 0x0101010101010101L)
        commit(length, SendTarget.ConnectedPeer)
    }

    private fun receiveAll(
        io: NativeIo,
        expected: Int,
    ): List<Int> {
        val buffers = List(BATCH) { ReceiveBuffer(it, native(DATAGRAM)) }
        val batch = ReceiveBatch(FixedPool(BATCH) { buffers[it] }, BatchLimits(BATCH, DATAGRAM))
        val got = ArrayList<Int>()
        val until = System.nanoTime() + 5_000_000_000L
        while (got.size < expected && System.nanoTime() < until) {
            if (io.receive(batch) is ReceiveBatchResult.Got) {
                for (i in 0 until batch.count) got.add(batch.lengthAt(i))
            } else {
                Thread.sleep(1)
            }
            batch.release()
        }
        return got
    }

    private fun native(size: Int): NativeBytes =
        assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(size))).bytes.also { owned.add(it) }

    private companion object {
        const val BATCH = 16
        const val DATAGRAM = 1500
        const val LINK_LIMIT = 1252
        const val SEGMENTED = 2
        const val CODE_SHIFT = 32
        const val EMSGSIZE = 2L
    }
}
