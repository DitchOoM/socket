package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.loopGateOfThisStack
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What a 64-byte echo costs the core client through the public 5.0 surface ([QuicConnection.openStream], `StreamOpen`,
 * [com.ditchoom.socket.quic.QuicSendStream.write] and the scoped [com.ditchoom.socket.quic.QuicReceiveStream.read])
 * against the internal path [CoreQuicAllocationTests] gates, over the same server, in the same run. Bytes are the
 * loop's and the caller's exact per-thread counters; resumes are the caller dispatcher's dispatches, each one a
 * continuation the loop resumed. The internal rows are printed only; the public rows are gated.
 *
 * Measured on JDK 21, macOS: caller 992 B per echo on one stream (internal 912) and 2,528 B per new-stream echo
 * (internal 2,392), 2 and 5 resumes, the loop under 1 B. The public path's extra bytes are its wrappers: the
 * `CoreSendStream.write` and `CoreReceiveStream.read` suspend frames, the read's block and its boxed `ScopedRead`, and per
 * new stream the `CoreBidiStream`, its two halves and their state cells, and the boxed `StreamOpen`. Each gate sits about
 * 5 % over its measurement.
 */
class PublicPathAllocationTests {
    @Test
    fun anEchoOnOneStreamThroughThePublicPathAllocatesNoMoreThanItsGate() =
        measure(PublicOneStream, loopGate = loopGateOfThisStack(LOOP_GATE, JDK_LOOP_GATE_ONE), callerGate = PUBLIC_CALLER_GATE_ONE)

    @Test
    fun anEchoOnANewStreamThroughThePublicPathAllocatesNoMoreThanItsGate() =
        measure(PublicNewStream, loopGate = loopGateOfThisStack(LOOP_GATE, JDK_LOOP_GATE_NEW), callerGate = PUBLIC_CALLER_GATE_NEW)

    @Test
    fun theInternalPathsEchoesAreReportedBesideThem() {
        measure(InternalOneStream, loopGate = Int.MAX_VALUE, callerGate = Int.MAX_VALUE)
        measure(InternalNewStream, loopGate = Int.MAX_VALUE, callerGate = Int.MAX_VALUE)
    }

    /** One echo from the caller's side, on the connection's internal or public shape. */
    private sealed interface Path {
        val label: String

        suspend fun prepare(client: CoreClient)

        suspend fun echo(payload: PlatformBuffer)
    }

    private object InternalOneStream : Path {
        override val label = "internal, one stream"
        private lateinit var stream: QuicByteStream

        override suspend fun prepare(client: CoreClient) {
            stream = client.connection.openStream()
        }

        override suspend fun echo(payload: PlatformBuffer) = internalRoundTrip(stream, payload)
    }

    private object InternalNewStream : Path {
        override val label = "internal, new stream per echo"
        private lateinit var connection: CoreConnection

        override suspend fun prepare(client: CoreClient) {
            connection = client.connection
        }

        override suspend fun echo(payload: PlatformBuffer) {
            val stream = connection.openStream()
            internalRoundTrip(stream, payload)
            (stream as HalfCloseable).shutdownSend()
            val end = stream.read(10.seconds)
            if (end !== ReadResult.End) fail("the server must finish its side once ours is finished: $end")
        }
    }

    private object PublicOneStream : Path {
        override val label = "public, one stream"
        private lateinit var stream: QuicStream

        override suspend fun prepare(client: CoreClient) {
            stream =
                client.connection
                    .public()
                    .openStream()
                    .getOrElse { fail("not opened: $it") }
        }

        override suspend fun echo(payload: PlatformBuffer) = publicRoundTrip(stream, payload)
    }

    private object PublicNewStream : Path {
        override val label = "public, new stream per echo"
        private lateinit var connection: QuicConnection

        override suspend fun prepare(client: CoreClient) {
            connection = client.connection.public()
        }

        override suspend fun echo(payload: PlatformBuffer) {
            val stream = connection.openStream().getOrElse { fail("not opened: $it") }
            publicRoundTrip(stream, payload)
            if (stream.send.finish() !== StreamWrite.Written) fail("the finish was not written")
            stream.receive.read { Unit }.fold({ fail("the server sent more than the echo") }, { end ->
                if (end !== ScopedRead.End.PeerFinished) fail("the server must finish its side once ours is finished: $end")
            })
        }
    }

    private fun measure(
        path: Path,
        loopGate: Int,
        callerGate: Int,
    ) = runBlocking {
        withDescriptorsAbove127 {
            skipOnMissingNativeLib(PublicPathAllocationTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val dispatcher = CountingDispatcher(CALLER_THREAD)
                    val payload =
                        BufferFactory.network().allocate(PAYLOAD).apply {
                            repeat(PAYLOAD) { writeByte(it.toByte()) }
                            resetForRead()
                        }
                    val done = CompletableFuture<kotlin.Result<Report>>()
                    suspend { run(client, path, payload, dispatcher) }
                        .startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                    val report = done.get(120, TimeUnit.SECONDS).getOrThrow()
                    val line =
                        "[public path allocation] %s | loop %.1f B/echo | caller %.1f B/echo | resumes %.2f/echo | dispatches %.2f/echo\n%s"
                            .format(path.label, report.loop, report.caller, report.dispatches, report.dispatches, report.where)
                    println(line)
                    client.connection.close(QuicError.NoError)
                    dispatcher.close()
                    assertTrue(
                        report.loop <= loopGate && report.caller <= callerGate,
                        "%s: loop %.1f B/echo (gate %d), caller %.1f B/echo (gate %d)\n%s"
                            .format(path.label, report.loop, loopGate, report.caller, callerGate, report.where),
                    )
                }
            }
        }
    }

    /** Runs on the caller's thread: warms up, then counts each client thread's bytes and the caller's resumes per echo. */
    private suspend fun run(
        client: CoreClient,
        path: Path,
        payload: PlatformBuffer,
        dispatcher: CountingDispatcher,
    ): Report {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        path.prepare(client)
        repeat(WARM_UP) { path.echo(payload) }
        val loopId =
            Thread
                .getAllStackTraces()
                .keys
                .first { it.name == client.loop.name }
                .id
        val callerId = Thread.currentThread().id
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
        recording.start()
        val from = Instant.now()
        val loopBefore = threads.getThreadAllocatedBytes(loopId)
        val callerBefore = threads.getThreadAllocatedBytes(callerId)
        val dispatchesBefore = dispatcher.dispatches.get()
        repeat(MEASURED) { path.echo(payload) }
        val caller = (threads.getThreadAllocatedBytes(callerId) - callerBefore).toDouble() / MEASURED
        val loop = (threads.getThreadAllocatedBytes(loopId) - loopBefore).toDouble() / MEASURED
        val dispatches = (dispatcher.dispatches.get() - dispatchesBefore).toDouble() / MEASURED
        recording.stop()
        return Report(loop, caller, dispatches, classes(recording, from))
    }

    /** The caller's sampled allocation by class, in bytes per echo: an estimate that names the objects, never gated. */
    private fun classes(
        recording: jdk.jfr.Recording,
        from: Instant,
    ): String {
        val dump =
            java.nio.file.Files
                .createTempFile("public-path", ".jfr")
        recording.dump(dump)
        val byClass = HashMap<String, Long>()
        allocationSamples(dump) { it == CALLER_THREAD }
            .filter { it.startTime >= from }
            .forEach { e ->
                val frames =
                    e.stackTrace
                        ?.frames
                        .orEmpty()
                        .take(3)
                        .joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                byClass.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        return byClass.entries
            .sortedByDescending { it.value }
            .take(25)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

    private class Report(
        val loop: Double,
        val caller: Double,
        val dispatches: Double,
        val where: String,
    )

    private companion object {
        const val CALLER_THREAD = "public-path-caller"
        const val LOOP_GATE = 8
        const val JDK_LOOP_GATE_ONE = 130
        const val JDK_LOOP_GATE_NEW = 260
        const val PUBLIC_CALLER_GATE_ONE = 1_050
        const val PUBLIC_CALLER_GATE_NEW = 2_650
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
    }
}

private suspend fun internalRoundTrip(
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

private suspend fun publicRoundTrip(
    stream: QuicStream,
    payload: PlatformBuffer,
) {
    payload.position(0)
    if (stream.send.write(payload) !== StreamWrite.Written) fail("the echo's write was not written")
    var received = 0
    while (received < payload.limit()) {
        received += stream.receive.read { it.remaining() }.getOrElse { fail("the echo ended early: $it") }
    }
}
