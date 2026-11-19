package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.network
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The receive slots stay armed between receives, so a receive costs the pool about one operation per datagram it handed
 * up, and every buffer is in exactly one place (its slot, the batch, the pool) through stop, reopen and a replaced socket.
 */
class ReceiveSlotsTests {
    private val shim by lazy { loadNetShim() }
    private val owned = ArrayList<NativeBytes>()
    private val closers = ArrayList<NativeIo>()

    @AfterTest
    fun freeAll() {
        closers.forEach { it.close() }
        owned.forEach { it.free() }
    }

    @Test
    fun aReceiveCostsThePoolAboutOneOperationPerDatagramItHandsUp() {
        val (server, client) = pair()
        val pool = poolOf()
        val batch = ReceiveBatch(pool, LIMITS)
        send(client, 4)
        drain(server, batch, 4)
        repeat(5) {
            val before = pool.operations()
            send(client, 2)
            drain(server, batch, 2)
            val cost = pool.operations() - before
            assertEquals(true, cost <= 8, "two datagrams cost $cost pool operations; the slots are not kept armed")
        }
        server.receive(batch)
        val idle = pool.operations()
        assertIs<ReceiveBatchResult.Nothing>(server.receive(batch))
        assertEquals(idle, pool.operations(), "a receive that finds nothing touches the pool")
    }

    @Test
    fun stopAndReopenWithArmedSlotsReturnEveryBufferToThePoolOnce() {
        val (server, client) = pair()
        val pool = poolOf()
        val batch = ReceiveBatch(pool, LIMITS)
        send(client, 3)
        drain(server, batch, 3)
        assertEquals(SLOTS, batch.slots.armed() + pool.available(), "released and armed buffers are every buffer")
        assertIs<Reopened.SameEndpoint>(server.reopen())
        send(client, 2)
        val got = drain(server, batch, 2)
        assertEquals(listOf(1, 1), got.map { it.second }, "bytes of a datagram received after the reopen")
        batch.disarm()
        assertEquals(0, batch.slots.armed())
        assertEquals(SLOTS, pool.available())
        batch.disarm()
        assertEquals(SLOTS, pool.available(), "a second disarm gives nothing back")
        val indices = HashSet<Int>()
        while (true) {
            val taken = pool.tryTake()
            if (taken is Taken.Empty) break
            indices.add((taken as ReceiveBuffer).index)
        }
        assertEquals(SLOTS, indices.size, "every buffer is in the pool exactly once")
    }

    @Test
    fun aReplacedSocketReceivesIntoTheSameArmedSlotsAndNoBufferIsHandedUpTwice() {
        val (server, client) = pair()
        val pool = poolOf()
        val batch = ReceiveBatch(pool, LIMITS)
        send(client, 3)
        drain(server, batch, 3)
        val (replacement, other) = pair()
        send(other, 5)
        var total = 0
        val until = System.nanoTime() + 5_000_000_000L
        while (total < 5 && System.nanoTime() < until) {
            if (replacement.receive(batch) is ReceiveBatchResult.Nothing) Thread.sleep(1)
            val held = (0 until batch.count).map { batch.bufferAt(it).index }
            assertEquals(held.size, held.toSet().size, "a buffer handed up twice in one batch")
            for (i in 0 until batch.count) {
                assertEquals(1, batch.bufferAt(i).bytes.byteAt(0).toInt(), "datagram bytes through the replaced socket")
            }
            total += batch.count
            assertEquals(SLOTS, batch.slots.armed() + batch.count + pool.available(), "a buffer is lost or in two places")
            batch.release()
        }
        assertEquals(5, total)
        batch.disarm()
        assertEquals(SLOTS, pool.available())
    }

    /** Receives until [expected] datagrams came, releasing each batch; (length, first byte) of each. */
    private fun drain(
        io: NativeIo,
        batch: ReceiveBatch,
        expected: Int,
    ): List<Pair<Int, Int>> {
        val got = ArrayList<Pair<Int, Int>>()
        val until = System.nanoTime() + 5_000_000_000L
        while (got.size < expected && System.nanoTime() < until) {
            when (io.receive(batch)) {
                is ReceiveBatchResult.Nothing -> Thread.sleep(1)
                else -> for (i in 0 until batch.count) got.add(batch.lengthAt(i) to batch.bufferAt(i).bytes.byteAt(0).toInt())
            }
            batch.release()
        }
        assertEquals(expected, got.size)
        return got
    }

    private fun send(
        from: NativeIo,
        count: Int,
    ) {
        val batch = SendBatch(native(count * DATAGRAM), BatchLimits(count, DATAGRAM))
        repeat(count) {
            val at = batch.nextOffset()
            for (b in 0 until 1200 step 8) batch.storage.putLong(at + b, 0x0101010101010101L)
            batch.commit(1200, SendTarget.ConnectedPeer)
        }
        assertEquals(SendBatchResult.AllTaken, from.send(batch))
    }

    private fun poolOf(): FixedPool<ReceiveBuffer> {
        val buffers = List(SLOTS) { ReceiveBuffer(it, native(DATAGRAM)) }
        return FixedPool(SLOTS) { buffers[it] }
    }

    /** The first receives what the second sends. */
    private fun pair(): Pair<NativeIo, NativeIo> {
        assumeNativeStack()
        val server = open(SocketPeer.Unconnected)
        val client = open(SocketPeer.Connected(server.second))
        return server.first to client.first
    }

    private fun open(peer: SocketPeer): Pair<NativeIo, PathKey> {
        val socket =
            assertIs<NativeOpened.Opened>(
                NativeSocket.open(
                    shim,
                    native(NativeSocket.SPEC_BYTES),
                    InetSocketAddress("127.0.0.1", 0).toPathKey(),
                    peer,
                    1 shl 20,
                    1 shl 20,
                    UdpBatching.Newest,
                    SLOTS,
                ),
            ).socket
        val io = NativeIo(shim, socket, listOf(native(NativeIo.SEND_RECORD * SLOTS)), native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE))
        closers.add(io)
        return io to socket.local
    }

    private fun native(size: Int): NativeBytes =
        assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(size))).bytes.also { owned.add(it) }

    private companion object {
        const val SLOTS = 16
        const val DATAGRAM = 1500
        val LIMITS = BatchLimits(SLOTS, DATAGRAM)
    }
}
