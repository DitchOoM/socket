package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.socket.nio.SelectorServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.LongAdder
import kotlin.concurrent.thread

/**
 * Many connections echoing 64 bytes against this library's server: the server under test is `nio2` (the default
 * listener) or `nio` ([SelectorServerSocket]); the load comes from plain NIO client threads that cost the
 * server nothing. Prints throughput, process CPU per operation minus the load generator's, and threads.
 *
 * `-Dconns=1024 -Dsecs=4 -Dbackend=nio2|nio -Ddispatcher=io|default`: where the echo coroutines run.
 */
object ManyConnectionEcho {
    @JvmStatic
    fun main(args: Array<String>) {
        val conns = System.getProperty("conns", "1024").toInt()
        val secs = System.getProperty("secs", "4").toLong()
        val backend = System.getProperty("backend", "nio2")
        val dispatcher =
            when (System.getProperty("dispatcher", "io")) {
                "default" -> Dispatchers.Default
                else -> Dispatchers.IO
            }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server: ServerSocket = if (backend == "nio") SelectorServerSocket() else ServerSocket.allocate()
        val port =
            runBlocking {
                val accepted = server.bind(0, "127.0.0.1", 4096)
                scope.launch {
                    accepted.collect { peer ->
                        launch {
                            try {
                                while (true) {
                                    when (val r = peer.read(kotlin.time.Duration.INFINITE)) {
                                        is ReadResult.Data ->
                                            try {
                                                peer.writeFully(r.buffer, kotlin.time.Duration.INFINITE)
                                            } finally {
                                                r.buffer.freeIfNeeded()
                                            }
                                        else -> break
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                peer.close()
                            }
                        }
                    }
                }
                server.port()
            }
        val count = LongAdder()
        val gens = minOf(4, conns)
        val loadThreads =
            List(gens) { g ->
                thread(name = "cli-$g", isDaemon = true) { drive(port, conns / gens, count) }
            }
        Thread.sleep(3000)
        val tmx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

        fun loadGenCpu() =
            loadThreads.sumOf { t ->
                tmx.allThreadIds.firstOrNull { id -> tmx.getThreadInfo(id)?.threadName == t.name }?.let { tmx.getThreadCpuTime(it) }
                    ?: 0L
            }
        val c0 = count.sum()
        val cpu0 = os.processCpuTime
        val gen0 = loadGenCpu()
        val t0 = System.nanoTime()
        Thread.sleep(secs * 1000)
        val ops = count.sum() - c0
        val wall = System.nanoTime() - t0
        val cpu = os.processCpuTime - cpu0
        val gen = loadGenCpu() - gen0
        println(
            "MANY backend=$backend dispatcher=${System.getProperty(
                "dispatcher",
                "io",
            )} conns=$conns ops_per_s=%.0f server_cpu_us_per_op=%.1f total_cpu_us_per_op=%.1f threads=%d"
                .format(ops * 1e9 / wall, (cpu - gen) / 1e3 / ops, cpu / 1e3 / ops, Thread.activeCount()),
        )
        println("TOTALOPS ${count.sum()}")
        runBlocking { server.close() }
        scope.cancel()
        System.exit(0)
    }

    /** One selector thread driving [n] connections: write 64 bytes, wait for the 64-byte echo, repeat. */
    private fun drive(
        port: Int,
        n: Int,
        count: LongAdder,
    ) {
        val selector = Selector.open()
        val msg = ByteBuffer.allocateDirect(64).also { b -> repeat(64) { b.put('r'.code.toByte()) } }
        repeat(n) {
            val ch = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            ch.socket().tcpNoDelay = true
            ch.configureBlocking(false)
            ch.register(selector, SelectionKey.OP_READ, ByteBuffer.allocateDirect(64))
            msg.clear()
            ch.write(msg)
        }
        while (true) {
            selector.select()
            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()
                val ch = key.channel() as SocketChannel
                val buf = key.attachment() as ByteBuffer
                val read = ch.read(buf)
                if (read < 0) {
                    key.cancel()
                    continue
                }
                if (!buf.hasRemaining()) {
                    buf.clear()
                    count.increment()
                    msg.clear()
                    ch.write(msg)
                }
            }
        }
    }
}
