package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.testing.NioEcho
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
        val server = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
        server.configureBlocking(false)
        val client = DatagramChannel.open().connect(server.localAddress)
        val echo = NioEcho(server, client.localAddress as InetSocketAddress, "core-loop-test")

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
        echo.loop.close { closed.countDown() }
        assertTrue(closed.await(5, TimeUnit.SECONDS), "the loop closed")
        echo.thread.join(5_000)
        assertTrue(!echo.thread.isAlive, "the loop thread ended")
        assertTrue(!server.isOpen, "the loop closed its socket")
        assertTrue(echo.loop.counts.waits > 0, "the loop slept between datagrams instead of spinning")
        client.close()
    }

    private companion object {
        const val ROUND_TRIPS = 2_000
    }
}
