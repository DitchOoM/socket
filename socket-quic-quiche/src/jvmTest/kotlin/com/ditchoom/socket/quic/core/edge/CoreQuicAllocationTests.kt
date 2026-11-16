package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the core client allocates per 64-byte echo against the 4.x server, split into the loop thread and the caller's
 * thread, measured with the JVM's per-thread allocation counter after warm-up; the server's threads are not counted.
 *
 * The harness is built not to allocate on its own account: buffers come from `BufferFactory.network()`, whose pool turns
 * a result buffer into a reuse, and the caller's thread takes tasks from a bounded array queue, which allocates no node
 * per hand-off. What is left is what a call forces on any host, all of it on the caller's thread: the continuations of
 * each suspend call, the operation object that carries a call into the connection, a parked read's wait and the watch on
 * its caller's job, the [ReadResult.Data] box, and, for a new stream, its handle objects and the open's continuation. The
 * loop answers a parked read without allocating, so its bytes do not depend on how often reads park; what it allocates is
 * the connection's timer re-arms, 0 to 7 bytes per echo. The caller's counter repeats to within a few percent across
 * runs: about 780 to 830 and 1,950 to 2,090 bytes on JDK 21 and 25. The gates leave a margin above that for JIT inlining
 * that differs by JDK and host. The test holds 160 descriptors first, so the JDK selector's boxed descriptor per wake
 * (16 B, design 9.3) is in every run and the gates include it.
 *
 * The bytes of objects whose class is in `com.ditchoom.socket.quic.core` are reported from a JFR allocation sample as
 * what the core itself allocates on the caller's thread. A sample is an estimate that swings by a factor of two between
 * runs, so it is printed and listed in a failure message, never gated.
 */
class CoreQuicAllocationTests {
    @Test
    fun anEchoOnOneStreamAllocatesOnlyWhatTheCallersApiForces() =
        measure("one stream", OneStream, loopGate = loopGate(LOOP_GATE_ONE, JDK_LOOP_GATE_ONE), callerGate = CALLER_GATE_ONE)

    @Test
    fun anEchoOnANewStreamAllocatesOnlyWhatTheCallersApiForces() =
        measure("new stream per echo", NewStream, loopGate = loopGate(LOOP_GATE_NEW, JDK_LOOP_GATE_NEW), callerGate = CALLER_GATE_NEW)

    /**
     * The loop's gate on this process's stack. The native stack boxes nothing (under 2 B/echo measured on macOS); the
     * JDK stack, left on Windows only, adds the selector's boxed descriptor per wake.
     */
    private fun loopGate(
        native: Int,
        jdk: Int,
    ): Int =
        when (netStackOfThisRuntime) {
            is NetStack.Native -> native
            is NetStack.Jdk -> jdk
        }

    /** One echo from the caller's side. */
    private sealed interface Shape {
        suspend fun echo(
            connection: CoreConnection,
            shared: com.ditchoom.socket.quic.QuicByteStream,
            payload: PlatformBuffer,
        )
    }

    private object OneStream : Shape {
        override suspend fun echo(
            connection: CoreConnection,
            shared: com.ditchoom.socket.quic.QuicByteStream,
            payload: PlatformBuffer,
        ) = roundTrip(shared, payload)
    }

    private object NewStream : Shape {
        override suspend fun echo(
            connection: CoreConnection,
            shared: com.ditchoom.socket.quic.QuicByteStream,
            payload: PlatformBuffer,
        ) {
            val stream = connection.openStream()
            roundTrip(stream, payload)
            (stream as HalfCloseable).shutdownSend()
            val end = stream.read(10.seconds)
            if (end !== ReadResult.End) fail("the server must finish its side once ours is finished: $end")
        }
    }

    private fun measure(
        name: String,
        shape: Shape,
        loopGate: Int,
        callerGate: Int,
    ) = runBlocking {
        withDescriptorsAbove127 {
            skipOnMissingNativeLib(CoreQuicAllocationTests::class) {
                withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val dispatcher = ArrayQueueDispatcher(CALLER_THREAD)
                    val payload =
                        BufferFactory.network().allocate(PAYLOAD).apply {
                            repeat(PAYLOAD) { writeByte(it.toByte()) }
                            resetForRead()
                        }
                    val done = CompletableFuture<kotlin.Result<Report>>()
                    suspend { run(client, shape, payload) }
                        .startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                    val report = done.get(120, TimeUnit.SECONDS).getOrThrow()
                    println(
                        "[core quic allocation: $name] loop %.1f B/echo, caller core-owned %.1f B/echo, caller total %.1f B/echo\n%s"
                            .format(
                                report.loop,
                                report.callerCore,
                                report.caller,
                                report.where,
                            ),
                    )
                    client.connection.close(QuicError.NoError)
                    dispatcher.close()
                    assertTrue(
                        report.loop <= loopGate && report.caller <= callerGate,
                        "$name: loop %.1f B/echo (gate %d), caller total %.1f (gate %d), caller core-owned %.1f (not gated), sampled from:\n%s"
                            .format(
                                report.loop,
                                loopGate,
                                report.caller,
                                callerGate,
                                report.callerCore,
                                report.where,
                            ),
                    )
                }
            }
        }
    }

    /** Runs on the caller's thread: warms up, then counts the bytes each of the client's two threads allocates. */
    private suspend fun run(
        client: CoreClient,
        shape: Shape,
        payload: PlatformBuffer,
    ): Report {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val shared = client.connection.openStream()
        repeat(WARM_UP) { shape.echo(client.connection, shared, payload) }
        val loopId = threadId(client.loop.name)
        val callerId = Thread.currentThread().id
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
        recording.start()
        val loopBefore = threads.getThreadAllocatedBytes(loopId)
        val callerBefore = threads.getThreadAllocatedBytes(callerId)
        val from = Instant.now()
        repeat(MEASURED) { shape.echo(client.connection, shared, payload) }
        val callerBytes = (threads.getThreadAllocatedBytes(callerId) - callerBefore).toDouble() / MEASURED
        val loopBytes = (threads.getThreadAllocatedBytes(loopId) - loopBefore).toDouble() / MEASURED
        recording.stop()
        return breakdown(recording, from, loopBytes, callerBytes)
    }

    /** An echo server that finishes its side of a stream once the client finishes its own, so streams are released. */
    private suspend fun withClosingEchoServer(body: suspend CoroutineScope.(port: Int) -> Unit) {
        withQuicServer(port = 0, tlsConfig = coreTlsConfig(), quicOptions = coreTestOptions) {
            coroutineScope {
                val server =
                    launch(Dispatchers.IO) {
                        connections {
                            while (true) {
                                val stream = acceptStream()
                                launch {
                                    // The client closing the connection ends the read with a QuicCloseException; uncaught here it would end the
                                    // whole process on Android.
                                    try {
                                        while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                                    } catch (closed: QuicCloseException) {
                                        // The peer is gone: the echo is over.
                                    }
                                    stream.close()
                                }
                            }
                        }
                    }
                body(port)
                server.cancel()
            }
        }
    }

    private class Report(
        val loop: Double,
        val caller: Double,
        val callerCore: Double,
        val where: String,
    )

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    /**
     * The allocation sites on the client's two threads since [from]: the top ones in bytes per echo, and the caller's
     * bytes of objects whose class is the core's own, other than this test's.
     */
    private fun breakdown(
        recording: jdk.jfr.Recording,
        from: Instant,
        loop: Double,
        caller: Double,
    ): Report {
        val dump =
            java.nio.file.Files
                .createTempFile("core-quic", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        var callerCore = 0L
        allocationSamples(dump) { it.startsWith(LOOP_THREAD) || it == CALLER_THREAD }
            .filter { it.startTime >= from }
            .forEach { e ->
                val all = e.stackTrace?.frames.orEmpty()
                val frames =
                    all.take(7).joinToString(" < ") {
                        "${it.method.type.name.substringAfterLast('.')}.${it.method.name}"
                    }
                if (e.thread?.javaName == CALLER_THREAD && isCore(e.getClass("objectClass").name)) callerCore += e.getLong("weight")
                sites.merge("[${e.thread?.javaName}] ${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        val where =
            sites.entries
                .sortedByDescending { it.value }
                .take(30)
                .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
        return Report(loop, caller, callerCore.toDouble() / MEASURED, where)
    }

    private fun isCore(className: String) = className.startsWith("com.ditchoom.socket.quic.core.") && !className.startsWith(TEST_CLASS)

    private companion object {
        const val CALLER_THREAD = "core-alloc-caller"
        const val TEST_CLASS = "com.ditchoom.socket.quic.core.edge.CoreQuicAllocationTests"
        const val LOOP_GATE_ONE = 8
        const val JDK_LOOP_GATE_ONE = 130
        const val CALLER_GATE_ONE = 950
        const val LOOP_GATE_NEW = 8
        const val JDK_LOOP_GATE_NEW = 260
        const val CALLER_GATE_NEW = 2_400
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
    }
}

/** Writes [payload] on [stream] and reads it back, releasing each result buffer. */
private suspend fun roundTrip(
    stream: com.ditchoom.socket.quic.QuicByteStream,
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

/**
 * Runs a caller's tasks on one thread of its own, taking them from a bounded array queue so a hand-off allocates no
 * queue node. The queue holds the few tasks one sequential caller has in flight.
 */
internal class ArrayQueueDispatcher(
    name: String,
) : CoroutineDispatcher() {
    private val queue = ArrayBlockingQueue<Runnable>(QUEUE)
    private val thread =
        Thread({
            try {
                while (true) queue.take().run()
            } catch (_: InterruptedException) {
                // closed
            }
        }, name).apply { isDaemon = true }

    init {
        thread.start()
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = queue.put(block)

    fun close() = thread.interrupt()

    private companion object {
        const val QUEUE = 64
    }
}
