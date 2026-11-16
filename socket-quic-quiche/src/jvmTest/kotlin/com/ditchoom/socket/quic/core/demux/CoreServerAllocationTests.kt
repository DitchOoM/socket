package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the server's loop thread allocates per 64-byte echo from one connection, measured with the JVM's per-thread
 * allocation counter after warm-up. The echo handler runs on the application's threads and enters the connection in
 * place, so what is counted is the loop's own work for one datagram in and one datagram out. That work allocates nothing:
 * one peer keeps sending, so the JDK hands back the same sender object and the seam reuses its path key, the connection
 * is found in the route table by its id without a key object, and quiche reads the datagram where the socket wrote it.
 * About 104 bytes per echo remain, and both are what a suspended read forces on whoever resumes it: the task kotlinx
 * makes to dispatch the resumed read to the application's dispatcher, and the completed-continuation state it keeps so a
 * cancelled read can free its buffer. Buffers come from the pooled `BufferFactory.network()`, so the count does not depend on how the JDK frees a native segment. The gate is twice the measurement, for JIT inlining that differs by JDK and host.
 */
class CoreServerAllocationTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    /** Client loops of this class's own, so connections it leaves ending never share a thread with another test's. */
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun theServerLoopAllocatesLittlePerEcho() =
        runBlocking {
            withCoreEchoServer(factory = BufferFactory.network()) { server ->
                val client = connectCore(server.port, loops = loops, factory = BufferFactory.network())
                val stream = client.connection.openStream()
                val payload =
                    BufferFactory.network().allocate(PAYLOAD).apply {
                        repeat(PAYLOAD) { writeByte(it.toByte()) }
                        resetForRead()
                    }
                repeat(WARM_UP) { roundTrip(stream, payload) }
                val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val loop =
                    Thread
                        .getAllStackTraces()
                        .keys
                        .first { it.name == SERVER_LOOP_THREAD }
                        .id
                val recording = jdk.jfr.Recording()
                recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000000/s").withStackTrace()
                recording.start()
                val before = threads.getThreadAllocatedBytes(loop)
                repeat(MEASURED) { roundTrip(stream, payload) }
                val perEcho = (threads.getThreadAllocatedBytes(loop) - before).toDouble() / MEASURED
                recording.stop()
                println("[core server allocation] loop %.1f B/echo\n%s".format(perEcho, breakdown(recording)))
                client.connection.close(QuicError.NoError)
                assertTrue(perEcho <= LOOP_GATE, "the server loop allocated %.1f B/echo (gate %d)".format(perEcho, LOOP_GATE))
            }
        }

    /** The top allocation sites on the server's loop thread, in bytes per echo. */
    private fun breakdown(recording: jdk.jfr.Recording): String {
        val dump =
            java.nio.file.Files
                .createTempFile("core-server", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump) { it == SERVER_LOOP_THREAD }
            .forEach { e ->
                val frames =
                    e.stackTrace?.frames.orEmpty().take(6).joinToString(" < ") {
                        "${it.method.type.name.substringAfterLast('.')}.${it.method.name}"
                    }
                sites.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        return sites.entries
            .sortedByDescending { it.value }
            .take(12)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

    private suspend fun roundTrip(
        stream: com.ditchoom.socket.quic.QuicByteStream,
        payload: com.ditchoom.buffer.PlatformBuffer,
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
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
        const val LOOP_GATE = 210
    }
}
