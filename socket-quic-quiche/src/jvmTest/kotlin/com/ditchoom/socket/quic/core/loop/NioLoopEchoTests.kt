package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** A [Loop] on its own thread over a real non-blocking socket: datagrams in, the same bytes out, then a clean close. */
class NioLoopEchoTests {
    @Test
    fun aLoopThreadEchoesEveryDatagramByteExactAndClosesItsSocket() {
        val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
        val server = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
        server.configureBlocking(false)
        val send = SendBatch(BufferFactory.Default.allocate(limits.maxDatagrams * limits.maxDatagramSize), limits)
        val receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { ReceiveBuffer(it, BufferFactory.Default.allocate(limits.maxDatagramSize)) }, limits)
        val clock = LoopClock { System.nanoTime() }
        val loop = Loop(SelectorWait(server, clock), clock, NioOneByOne(server, send), EchoParts(), receive, send)
        val running = startLoop(loop, "core-loop-test")

        val client = DatagramChannel.open().connect(server.localAddress)
        val reply = ByteBuffer.allocate(1500)
        repeat(ROUND_TRIPS) { n ->
            val payload = ByteArray(1 + n % 1200) { (n + it).toByte() }
            client.write(ByteBuffer.wrap(payload))
            reply.clear()
            client.read(reply)
            reply.flip()
            assertContentEquals(payload, ByteArray(reply.remaining()).also { reply.get(it) }, "round trip $n")
        }

        val closed = CountDownLatch(1)
        loop.close { closed.countDown() }
        assertTrue(closed.await(5, TimeUnit.SECONDS), "the loop closed")
        running.thread.join(5_000)
        assertTrue(!running.thread.isAlive, "the loop thread ended")
        assertTrue(!server.isOpen, "the loop closed its socket")
        assertTrue(loop.counts.waits > 0, "the loop slept between datagrams instead of spinning")
        client.close()
    }

    private companion object {
        const val ROUND_TRIPS = 2_000
    }
}
