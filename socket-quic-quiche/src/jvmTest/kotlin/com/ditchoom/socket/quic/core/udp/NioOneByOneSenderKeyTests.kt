package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.testing.jdk
import com.ditchoom.socket.quic.core.testing.nativeBytes
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.fail

/**
 * A server's [NioOneByOne] hands a sender it heard before the key it made the first time. Peers take turns on a
 * server's socket, so the sender of one datagram is rarely the sender of the one before; a key made per datagram is an
 * allocation per datagram on the loop.
 */
class NioOneByOneSenderKeyTests {
    private val limits = BatchLimits(maxDatagrams = 4, maxDatagramSize = 1500)
    private val receiveBuffers = List(limits.maxDatagrams) { ReceiveBuffer(it, nativeBytes(limits.maxDatagramSize)) }
    private val send = SendBatch(nativeBytes(limits.maxDatagrams * limits.maxDatagramSize), limits)

    @Test
    fun aSenderHeardBeforeGetsTheKeyMadeTheFirstTime() {
        DatagramChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            server.configureBlocking(false)
            val to = server.localAddress
            DatagramChannel.open().use { a ->
                DatagramChannel.open().use { b ->
                    a.bind(InetSocketAddress("127.0.0.1", 0))
                    b.bind(InetSocketAddress("127.0.0.1", 0))
                    val io = NioOneByOne(server, listOf(SendView(send.storage, jdk(send.storage))), receiveBuffers.map { jdk(it.bytes) })
                    val batch = ReceiveBatch(FixedPool(limits.maxDatagrams) { receiveBuffers[it] }, limits)
                    val first = HashMap<Int, PathKey>()
                    repeat(ROUNDS) { round ->
                        for (sender in listOf(a, b)) {
                            sender.send(ByteBuffer.wrap(byteArrayOf(round.toByte())), to)
                            val key = receiveOne(io, batch)
                            assertEquals((sender.localAddress as InetSocketAddress).port, key.port)
                            val kept = first.getOrPut(key.port) { key }
                            assertSame(kept, key, "round $round: the key of a sender heard before")
                        }
                    }
                }
            }
        }
    }

    private fun receiveOne(
        io: NioOneByOne,
        batch: ReceiveBatch,
    ): PathKey {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            when (io.receive(batch)) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    val key = batch.fromAt(0)
                    assertEquals(1, batch.count)
                    batch.release()
                    return key
                }
                is ReceiveBatchResult.Nothing -> Thread.onSpinWait()
                is ReceiveBatchResult.Failed -> fail("the receive failed")
            }
        }
        fail("no datagram arrived")
    }

    private companion object {
        const val ROUNDS = 4
        const val WAIT_NANOS = 5_000_000_000L
    }
}
