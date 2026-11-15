package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the core client allocates per 64-byte echo against the 4.x server, split into the loop thread and the caller's
 * thread, measured with the JVM's per-thread allocation counter after warm-up; the server's threads are not counted.
 * The caller runs on a thread of its own, started without a debug-named coroutine, so neither a server coroutine nor
 * coroutine debugging allocates on it.
 *
 * What a caller's API forces, and so what the gates allow: a read's result buffer from the caller's factory (a native
 * segment with its arena, cleaner and session) and the boxed [ReadResult.Data] around it, and a continuation with its
 * cancellation handler for each suspend call. Per echo that measures about 570 bytes on the loop thread and 1,410 on the
 * caller's thread over one stream, and 1,120 and 3,240 when each echo opens a stream; the gates sit just above. Which thread
 * serves a read moves bytes between the two, so a failure message lists the allocation sites of both.
 */
class CoreQuicAllocationTests {
    @Test
    fun anEchoOnOneStreamAllocatesOnlyWhatTheCallersApiForces() = measure("one stream", OneStream, loopGate = 600, callerGate = 1_500)

    @Test
    fun anEchoOnANewStreamAllocatesOnlyWhatTheCallersApiForces() =
        measure("new stream per echo", NewStream, loopGate = 1_200, callerGate = 3_400)

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
        skipOnMissingNativeLib(CoreQuicAllocationTests::class) {
            withClosingEchoServer { port ->
                val client = connectCore(port)
                val callerThread = Executors.newSingleThreadExecutor { Thread(it, CALLER_THREAD) }
                val payload =
                    BufferFactory.Default.allocate(PAYLOAD).apply {
                        repeat(PAYLOAD) { writeByte(it.toByte()) }
                        resetForRead()
                    }
                val done = CompletableFuture<kotlin.Result<Report>>()
                val dispatcher = callerThread.asCoroutineDispatcher()
                suspend { run(client, shape, payload) }
                    .startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                val report = done.get(120, TimeUnit.SECONDS).getOrThrow()
                println(
                    "[core quic allocation: $name] loop %.1f B/echo, caller %.1f B/echo, total %.1f B/echo\n%s".format(
                        report.loop,
                        report.caller,
                        report.loop + report.caller,
                        report.where,
                    ),
                )
                client.connection.close(QuicError.NoError)
                callerThread.shutdown()
                assertTrue(
                    report.loop <= loopGate && report.caller <= callerGate,
                    "$name: loop %.1f B/echo (gate %d), caller %.1f B/echo (gate %d), sampled from:\n%s".format(
                        report.loop,
                        loopGate,
                        report.caller,
                        callerGate,
                        report.where,
                    ),
                )
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
        val loopId = threadId(LOOP_THREAD)
        val callerId = Thread.currentThread().id
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000000/s").withStackTrace()
        recording.start()
        val loopBefore = threads.getThreadAllocatedBytes(loopId)
        val callerBefore = threads.getThreadAllocatedBytes(callerId)
        val from = Instant.now()
        repeat(MEASURED) { shape.echo(client.connection, shared, payload) }
        val callerBytes = (threads.getThreadAllocatedBytes(callerId) - callerBefore).toDouble() / MEASURED
        val loopBytes = (threads.getThreadAllocatedBytes(loopId) - loopBefore).toDouble() / MEASURED
        recording.stop()
        return Report(loopBytes, callerBytes, breakdown(recording, from))
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
                                    while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
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
        val where: String,
    )

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    /** The top allocation sites on the client's two threads since [from], in bytes per echo. */
    private fun breakdown(
        recording: jdk.jfr.Recording,
        from: Instant,
    ): String {
        val dump =
            java.nio.file.Files
                .createTempFile("core-quic", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        jdk.jfr.consumer.RecordingFile
            .readAllEvents(dump)
            .filter { it.startTime >= from && (it.thread?.javaName == LOOP_THREAD || it.thread?.javaName == CALLER_THREAD) }
            .forEach { e ->
                val frames =
                    e.stackTrace?.frames.orEmpty().take(7).joinToString(" < ") {
                        "${it.method.type.name.substringAfterLast('.')}.${it.method.name}"
                    }
                sites.merge("[${e.thread?.javaName}] ${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        return sites.entries
            .sortedByDescending { it.value }
            .take(30)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

    private companion object {
        const val CALLER_THREAD = "core-alloc-caller"
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
