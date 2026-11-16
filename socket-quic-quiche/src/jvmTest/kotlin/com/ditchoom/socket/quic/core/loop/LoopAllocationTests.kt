package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.testing.NioEcho
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
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
 * counter after warm-up. The connection's quiche echoes from preallocated memory, so anything counted is the loop's,
 * the connection's and the socket implementation's own.
 *
 * The test first holds 160 descriptors, so its sockets sit above 127 as a server's do (design 9.3):
 * the JDK selector's `fdToKey.get(fd)` boxes every descriptor above 127, one `Integer` per ready key. A worker that
 * happened to have fewer open passed, one with more failed (Apple FFM: 16 B per echo). On the JDK stack the gate is
 * that boxing and nothing else: the native stack of design 9.1 takes its place and the gate returns to zero.
 */
class LoopAllocationTests {
    @Test
    fun theLoopAllocatesNothingPerEchoedDatagramButTheJdkSelectorsBoxing() {
        withDescriptorsAbove127 { measure() }
    }

    private fun measure() {
        val server = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
        server.configureBlocking(false)
        val client = DatagramChannel.open().connect(server.localAddress)
        val echo = NioEcho(server, client.localAddress as InetSocketAddress, "core-loop-alloc")
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val out = ByteBuffer.allocateDirect(1200)
        val back = ByteBuffer.allocateDirect(1500)

        fun echoes(times: Int) =
            repeat(times) {
                out.clear()
                client.write(out)
                back.clear()
                client.read(back)
            }

        echoes(WARM_UP)
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000000/s").withStackTrace()
        recording.start()
        val bytesBefore = threads.getThreadAllocatedBytes(echo.thread.id)
        val waitsBefore = echo.loop.counts.waits
        val started = System.nanoTime()
        echoes(MEASURED)
        val nanos = System.nanoTime() - started
        val perEcho = (threads.getThreadAllocatedBytes(echo.thread.id) - bytesBefore).toDouble() / MEASURED
        val wakesPerEcho = (echo.loop.counts.waits - waitsBefore).toDouble() / MEASURED
        recording.stop()
        val dump =
            java.nio.file.Files
                .createTempFile("core-loop", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump) { it == "core-loop-alloc" }.forEach { e ->
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
        echo.loop.close { closed.complete(Unit) }
        closed.get(5, TimeUnit.SECONDS)
        client.close()
        println(
            "[core-loop] loop thread allocates %.1f bytes per echo, %.2f selector wakes; round trip %.1f µs".format(
                perEcho,
                wakesPerEcho,
                nanos / 1_000.0 / MEASURED,
            ),
        )
        val notTheSelectorsBoxing = sites.keys.filterNot { it.startsWith(BOXED_KEY) && it.contains(SELECTOR_FRAME) }
        assertTrue(
            notTheSelectorsBoxing.isEmpty(),
            "the loop thread allocated at a site other than the JDK selector's boxing:\n%s".format(whereFrom),
        )
        assertTrue(
            perEcho < BOXED_INTEGER_BYTES * wakesPerEcho + 1.0,
            "the loop thread allocated %.1f bytes per echoed datagram, over one boxed descriptor per selector wake (%.2f), sampled from:\n%s"
                .format(perEcho, wakesPerEcho, whereFrom),
        )
    }

    private companion object {
        const val BOXED_INTEGER_BYTES = 16
        const val BOXED_KEY = "java.lang.Integer @ Integer.valueOf"
        const val SELECTOR_FRAME = "SelectorImpl.process"
        const val WARM_UP = 20_000
        const val MEASURED = 20_000
    }
}
