package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.LOOP_THREAD
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the loops allocate per 64-byte echo when [CONNECTIONS] client connections echo at once against a core server in
 * the same process: the client loops and the server's loop, counted together with the JVM's per-thread allocation
 * counter after warm-up. Many connections share each loop, so a loop serves several in one pass, callers find lanes and
 * copy slots taken, and connections' timers fire together: work one connection alone never reaches. The echo handlers
 * and callers run on the application's threads and are not counted.
 */
class CoreManyConnectionsAllocationTests {
    @get:Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun echoesOnManyConnectionsAllocateLittleOnTheLoops() =
        runBlocking {
            withDescriptorsAbove127 {
                withCoreEchoServer(factory = BufferFactory.network()) { server ->
                    val clients = List(CONNECTIONS) { connectCore(server.port, loops = loops, factory = BufferFactory.network()) }
                    val streams = clients.map { it.connection.openStream() }
                    val payloads = List(CONNECTIONS) { payload() }
                    echoes(streams, payloads, WARM_UP)
                    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                    val loopIds = loopThreads().map { it.id }
                    val recording = jdk.jfr.Recording()
                    recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000000/s").withStackTrace()
                    recording.start()
                    val before = loopIds.sumOf { threads.getThreadAllocatedBytes(it) }
                    echoes(streams, payloads, MEASURED)
                    val perEcho = (loopIds.sumOf { threads.getThreadAllocatedBytes(it) } - before).toDouble() / (CONNECTIONS * MEASURED)
                    recording.stop()
                    val where = breakdown(recording)
                    println(
                        "[core many-connection allocation] loops %.1f B/echo over %d connections\n%s".format(perEcho, CONNECTIONS, where),
                    )
                    clients.forEach { it.connection.close(QuicError.NoError) }
                    assertTrue(
                        perEcho <= LOOP_GATE,
                        "the loops allocated %.1f B/echo over %d connections (gate %d):\n%s".format(perEcho, CONNECTIONS, LOOP_GATE, where),
                    )
                }
            }
        }

    /** Every connection echoes [count] times, all at once. */
    private suspend fun echoes(
        streams: List<QuicByteStream>,
        payloads: List<PlatformBuffer>,
        count: Int,
    ) = coroutineScope {
        streams.indices
            .map { i -> async(Dispatchers.Default) { repeat(count) { roundTrip(streams[i], payloads[i]) } } }
            .awaitAll()
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private fun loopThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name.startsWith(LOOP_THREAD) || it.name == SERVER_LOOP_THREAD }

    /** The top allocation sites on the loop threads, in bytes per echo. */
    private fun breakdown(recording: jdk.jfr.Recording): String {
        val dump =
            java.nio.file.Files
                .createTempFile("core-many", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump) { it.startsWith(LOOP_THREAD) || it == SERVER_LOOP_THREAD }
            .forEach { e ->
                val frames =
                    e.stackTrace?.frames.orEmpty().take(8).joinToString(" < ") {
                        "${it.method.type.name.substringAfterLast('.')}.${it.method.name}:${it.lineNumber}"
                    }
                sites.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        return sites.entries
            .sortedByDescending { it.value }
            .take(15)
            .joinToString("\n") { "  %.2f B/echo %s".format(it.value.toDouble() / (CONNECTIONS * MEASURED), it.key) }
    }

    private suspend fun roundTrip(
        stream: QuicByteStream,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        stream.write(payload, 10.seconds)
        var received = 0
        while (received < payload.limit()) {
            val result = stream.read(10.seconds)
            if (result !is ReadResult.Data) fail("the echo ended early: $result")
            received += result.buffer.remaining()
            result.buffer.freeIfNeeded()
        }
    }

    private companion object {
        const val CONNECTIONS = 64
        const val PAYLOAD = 64
        const val WARM_UP = 500
        const val MEASURED = 1_000
        const val LOOP_GATE = 16
    }
}
