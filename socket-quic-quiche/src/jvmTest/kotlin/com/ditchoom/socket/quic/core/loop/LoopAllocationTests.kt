package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the loop thread allocates per datagram it receives and sends, measured with the JVM's per-thread allocation
 * counter after warm-up. The parts copy each datagram straight from its receive buffer into the send batch, so
 * anything counted is the loop's and the socket implementation's own.
 */
class LoopAllocationTests {
    @Test
    fun theLoopAllocatesNothingPerEchoedDatagram() {
        val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
        val server = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
        server.configureBlocking(false)
        val send = SendBatch(BufferFactory.Default.allocate(limits.maxDatagrams * limits.maxDatagramSize), address = 0L, limits)
        val receive =
            ReceiveBatch(
                FixedPool(limits.maxDatagrams) { ReceiveBuffer(it, BufferFactory.Default.allocate(limits.maxDatagramSize), address = 0L) },
                limits,
            )
        val clock = LoopClock { System.nanoTime() }
        val parts = CopyingEcho(send)
        val loop = Loop(SelectorWait(server, clock), clock, NioOneByOne(server, send), parts, receive, send)
        val running = startLoop(loop, "core-loop-alloc")
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        val client = DatagramChannel.open().connect(server.localAddress)
        val out = ByteBuffer.allocateDirect(1200)
        val back = ByteBuffer.allocateDirect(1500)

        fun echo(times: Int) =
            repeat(times) {
                out.clear()
                client.write(out)
                back.clear()
                client.read(back)
            }

        echo(WARM_UP)
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000000/s").withStackTrace()
        recording.start()
        val bytesBefore = threads.getThreadAllocatedBytes(running.thread.id)
        val started = System.nanoTime()
        echo(MEASURED)
        val nanos = System.nanoTime() - started
        val perEcho = (threads.getThreadAllocatedBytes(running.thread.id) - bytesBefore).toDouble() / MEASURED
        recording.stop()
        val dump =
            java.nio.file.Files
                .createTempFile("core-loop", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        jdk.jfr.consumer.RecordingFile.readAllEvents(dump).filter { it.thread?.javaName == "core-loop-alloc" }.forEach { e ->
            val frames =
                e.stackTrace?.frames.orEmpty().take(6).joinToString(" < ") {
                    "${it.method.type.name.substringAfterLast('.')}.${it.method.name}"
                }
            sites.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
        }
        val whereFrom =
            sites.entries
                .sortedByDescending { it.value }
                .take(
                    6,
                ).joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }

        val closed = CompletableFuture<Unit>()
        loop.close { closed.complete(Unit) }
        closed.get(5, TimeUnit.SECONDS)
        client.close()
        println("[core-loop] loop thread allocates %.1f bytes per echo; round trip %.1f µs".format(perEcho, nanos / 1_000.0 / MEASURED))
        assertTrue(perEcho < 1.0, "the loop thread allocated %.1f bytes per echoed datagram, sampled from:\n%s".format(perEcho, whereFrom))
    }

    /** Answers each datagram with its own bytes, copied from the receive buffer into the send batch. */
    private class CopyingEcho(
        private val send: SendBatch,
    ) : LoopParts {
        private var reply: SendTarget = SendTarget.ConnectedPeer

        override fun onDatagram(
            buffer: ReceiveBuffer,
            length: Int,
            from: PathKey,
            to: ArrivedOn,
        ) {
            if (send.room() === Room.Full) return
            val offset = send.nextOffset()
            send.storage.setLimit(send.storage.capacity)
            send.storage.position(offset)
            buffer.buffer.position(0)
            buffer.buffer.setLimit(length)
            send.storage.write(buffer.buffer)
            val target = reply
            if (target !is SendTarget.ServerReply || target.to !== from) reply = SendTarget.ServerReply(from, ReplySource.Undecodable)
            send.commit(length, reply)
        }

        override fun collect(out: SendBatch): Collected = Collected.Everything
    }

    private companion object {
        const val WARM_UP = 20_000
        const val MEASURED = 20_000
    }
}
