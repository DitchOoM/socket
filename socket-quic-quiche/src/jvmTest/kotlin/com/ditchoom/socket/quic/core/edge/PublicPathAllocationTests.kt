package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.JDK_BOXED_HANDLES_PER_OP
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the public 5.0 path costs, by the client's loop and caller threads' exact allocation counters, over a core
 * server. A 64-byte echo through [QuicConnection.openStream], `StreamOpen`, [com.ditchoom.socket.quic.QuicSendStream.write]
 * and the scoped [com.ditchoom.socket.quic.QuicReceiveStream.read] is measured against the internal path
 * [CoreQuicAllocationTests] gates, in the same run (the internal rows are printed only), with the caller dispatcher's
 * resumes. The shapes alpha01 ships are gated per operation: an echo on one stream, a new stream per echo with
 * `writeAndFinish`, and a 16 MiB download read by suspend reads.
 */
class PublicPathAllocationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** Takes every byte it is lent and counts them. */
    private class Take {
        var bytes = 0L

        fun all(buffer: ReadBuffer) {
            bytes += buffer.remaining()
            buffer.position(buffer.limit())
        }
    }

    @Test
    fun anEchoOnOneStreamThroughThePublicPathAllocatesNoMoreThanItsGate() =
        measure(PublicOneStream, loopGate = loopGateOfThisStack(LOOP_GATE, JDK_LOOP_GATE_ONE), callerGate = PUBLIC_CALLER_GATE_ONE)

    @Test
    fun anEchoOnANewStreamThroughThePublicPathAllocatesNoMoreThanItsGate() =
        measure(PublicNewStream, loopGate = loopGateOfThisStack(LOOP_GATE_NEW, JDK_LOOP_GATE_NEW), callerGate = PUBLIC_CALLER_GATE_NEW)

    @Test
    fun theInternalPathsEchoesAreReportedBesideThem() {
        measure(InternalOneStream, loopGate = Int.MAX_VALUE, callerGate = Int.MAX_VALUE)
        measure(InternalNewStream, loopGate = Int.MAX_VALUE, callerGate = Int.MAX_VALUE)
    }

    @Test
    fun anEchoOnOneStreamWithTheSuspendReadAndTheQueuedWrite() =
        measured { port ->
            val connection = connect(port)
            val (send, receive) = connection.openStream().getOrElse { fail("not opened: $it") }
            val payload = payloadOf(ECHO)
            val take = Take()

            suspend fun one() {
                payload.position(0)
                if (send.write(payload) !== StreamWrite.Written) fail("the ping was not written")
                val until = take.bytes + ECHO
                while (take.bytes < until) readOrFail(receive, take)
            }
            repeat(ECHO_WARM_UP) { one() }
            val from = Counters(Thread.currentThread().id)
            repeat(ECHOES) { one() }
            val row = from.per(ECHOES.toLong())
            report("echo 64 B on one stream, write then suspend reads of the reply, per echo", row)
            send.finish()
            row.gate(ECHO_CALLER_GATE, ECHO_LOOP_GATE)
        }

    @Test
    fun aNewStreamPerEchoWithTheSuspendReadAndTheQueuedWrite() =
        measured { port ->
            val connection = connect(port)
            val payload = payloadOf(ECHO)
            val take = Take()

            suspend fun one() {
                val (send, receive) = connection.openStream().getOrElse { fail("not opened: $it") }
                payload.position(0)
                if (send.writeAndFinish(payload) !== StreamWrite.Written) fail("the request was not written")
                while (true) {
                    when (val read = receive.read { take.all(it) }.fold({ Unit }, { it })) {
                        is ScopedRead.End.PeerFinished -> return
                        is Unit -> Unit
                        else -> fail("the echo ended with $read")
                    }
                }
            }
            repeat(NEW_WARM_UP) { one() }
            val from = Counters(Thread.currentThread().id)
            repeat(NEW_STREAMS) { one() }
            val row = from.per(NEW_STREAMS.toLong())
            report("new stream per echo (open, writeAndFinish, suspend reads to the end), per stream", row)
            row.gate(NEW_CALLER_GATE, NEW_LOOP_GATE)
        }

    @Test
    fun aDownloadReadWithTheSuspendRead() =
        measured { port ->
            val connection = connect(port)
            val (send, receive) = connection.openStream().getOrElse { fail("not opened: $it") }
            if (send.writeAndFinish(byteOf(DOWNLOAD)) !== StreamWrite.Written) fail("the request was not written")
            val take = Take()
            var reads = 0L
            while (take.bytes < WARM_BYTES) {
                readOrFail(receive, take)
                reads++
            }
            val from = Counters(Thread.currentThread().id)
            val warmReads = reads
            while (take.bytes < TOTAL) {
                readOrFail(receive, take)
                reads++
            }
            val row = from.per(reads - warmReads)
            report("16 MiB one way, per suspend read (${reads - warmReads} reads)", row)
            row.gate(DOWNLOAD_CALLER_GATE, DOWNLOAD_LOOP_GATE)
        }

    /**
     * Every object the caller's thread allocates in [BREAKDOWN_ECHOES] echoes of [path], by class and the call that made
     * it, per echo. Exact only without TLABs (`-PallocTestJvmArgs=-XX:-UseTLAB`), where JFR records every allocation as
     * an outside-TLAB event; a run with TLABs answers how to get it instead. Printed when a gate fails.
     */
    private fun breakdownOf(path: Path): String {
        val tlab =
            ManagementFactory
                .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean::class.java)
                .getVMOption("UseTLAB")
                .value
        if (tlab == "true") return "rerun with -PallocTestJvmArgs=-XX:-UseTLAB for every object this echo allocates"
        val out = StringBuilder()
        runBlocking {
            CoreQuicAllocationTests().withClosingEchoServer { port ->
                val client = connectCore(port, factory = BufferFactory.network())
                val dispatcher = CountingDispatcher(CALLER_THREAD)
                val payload =
                    BufferFactory.network().allocate(PAYLOAD).apply {
                        repeat(PAYLOAD) { writeByte(it.toByte()) }
                        resetForRead()
                    }
                val done = CompletableFuture<kotlin.Result<Pair<java.nio.file.Path, Long>>>()
                suspend {
                    path.prepare(client)
                    repeat(WARM_UP) { path.echo(payload) }
                    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                    val me = Thread.currentThread().id
                    val recording = jdk.jfr.Recording()
                    recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace()
                    recording.start()
                    val before = threads.getThreadAllocatedBytes(me)
                    repeat(BREAKDOWN_ECHOES) { path.echo(payload) }
                    val bytes = threads.getThreadAllocatedBytes(me) - before
                    recording.stop()
                    val dump =
                        java.nio.file.Files
                            .createTempFile("breakdown", ".jfr")
                    recording.dump(dump)
                    dump to bytes
                }.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                val (dump, bytes) = done.get(120, TimeUnit.SECONDS).getOrThrow()
                client.connection.close(QuicError.NoError)
                dispatcher.close()
                val rows = HashMap<String, LongArray>()
                jdk.jfr.consumer.RecordingFile
                    .readAllEvents(dump)
                    .filter { it.eventType.name == "jdk.ObjectAllocationOutsideTLAB" && it.thread?.javaName == CALLER_THREAD }
                    .forEach { e ->
                        val frames = e.stackTrace?.frames.orEmpty()
                        val site =
                            frames
                                .map { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                                .firstOrNull { !it.startsWith("Dispatched") && !it.contains("intercepted") } ?: "?"
                        val chain =
                            frames
                                .take(
                                    4,
                                ).joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                        val key = "${e.getClass("objectClass").name} | $site | $chain"
                        val row = rows.getOrPut(key) { LongArray(2) }
                        row[0]++
                        row[1] += e.getLong("allocationSize")
                    }
                java.nio.file.Files
                    .delete(dump)
                val total = rows.values.sumOf { it[1] }
                out.append(
                    "%s: counter %.1f B/echo, events %.1f B/echo over %d echoes (count and B per echo | class | made by | stack)\n".format(
                        path.label,
                        bytes.toDouble() / BREAKDOWN_ECHOES,
                        total.toDouble() / BREAKDOWN_ECHOES,
                        BREAKDOWN_ECHOES,
                    ),
                )
                rows.entries.sortedByDescending { it.value[1] }.forEach { (k, v) ->
                    out.append("  %.2f, %.1f | %s\n".format(v[0].toDouble() / BREAKDOWN_ECHOES, v[1].toDouble() / BREAKDOWN_ECHOES, k))
                }
            }
        }
        return out.toString()
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
                    if (report.loop > loopGate || report.caller > callerGate) {
                        fail(
                            "%s: loop %.1f B/echo (gate %d), caller %.1f B/echo (gate %d)\n%s\nevery object:\n%s"
                                .format(path.label, report.loop, loopGate, report.caller, callerGate, report.where, breakdownOf(path)),
                        )
                    }
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

    private suspend fun readOrFail(
        receive: QuicReceiveStream,
        take: Take,
    ) {
        receive.read { take.all(it) }.getOrElse { fail("the stream ended after ${take.bytes} B: $it") }
    }

    private fun measured(body: suspend (Int) -> Unit) =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(PublicPathAllocationTests::class) {
                    withCoreServer(onConnection = { serveRawStreams(it) }) { server -> body(server.port) }
                }
            }
        }

    private suspend fun connect(port: Int): QuicConnection =
        connectCore(port, loops = loops, factory = BufferFactory.network()).connection.public()

    /** A stream whose first read is the single byte [DOWNLOAD] gets [TOTAL] bytes back; any other is echoed until its FIN. */
    private suspend fun serveRawStreams(connection: CoreConnection) {
        val chunk = payloadOf(CHUNK)
        connection.handleStreams { stream ->
            when (val first = stream.read(10.seconds)) {
                is ReadResult.Data -> {
                    val buffer = first.buffer
                    if (buffer.remaining() == 1 && buffer.get(buffer.position()) == DOWNLOAD) {
                        buffer.freeIfNeeded()
                        repeat((TOTAL / CHUNK).toInt()) {
                            chunk.position(0)
                            stream.writeFully(chunk, 10.seconds)
                        }
                    } else {
                        stream.writeFully(buffer, 10.seconds)
                        buffer.freeIfNeeded()
                        while (true) {
                            when (val next = stream.read(10.seconds)) {
                                is ReadResult.Data -> {
                                    stream.writeFully(next.buffer, 10.seconds)
                                    next.buffer.freeIfNeeded()
                                }
                                else -> break
                            }
                        }
                    }
                }
                else -> Unit
            }
            stream.close()
        }
    }

    private class Row(
        val caller: Double,
        val loop: Double,
    ) {
        fun gate(
            callerGate: Double,
            loopGate: Double,
        ) = assertTrue(
            caller <= callerGate && loop <= stackLoopGate(loopGate),
            "caller %.1f B/op (gate %.0f), loop %.1f (gate %.0f)".format(caller, callerGate, loop, stackLoopGate(loopGate)),
        )
    }

    /** The client's loop and caller threads' allocation counters from when it is made. */
    private class Counters(
        private val callerThread: Long,
    ) {
        private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val loop =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name.startsWith(CLIENT_LOOP) && !it.name.startsWith(CLIENT_LANE) }
                .map { it.id }
        private val loopFrom = loop.sumOf { mx.getThreadAllocatedBytes(it) }
        private val callerFrom = mx.getThreadAllocatedBytes(callerThread)

        fun per(ops: Long): Row {
            val n = ops.toDouble()
            return Row(
                (mx.getThreadAllocatedBytes(callerThread) - callerFrom) / n,
                (loop.sumOf { mx.getThreadAllocatedBytes(it) } - loopFrom) / n,
            )
        }
    }

    private fun report(
        what: String,
        row: Row,
    ) = println("[public path allocation] %s: caller %.1f B/op, loop %.1f B/op".format(what, row.caller, row.loop))

    private companion object {
        const val CALLER_THREAD = "public-path-caller"
        const val CLIENT_LOOP = "quic-core-client"
        const val CLIENT_LANE = "quic-core-client-handler"
        const val DOWNLOAD: Byte = 'D'.code.toByte()
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
        const val BREAKDOWN_ECHOES = 500
        const val ECHO = 64
        const val CHUNK = 1024
        const val TOTAL = 16L * 1_048_576
        const val WARM_BYTES = 2L * 1_048_576
        const val ECHO_WARM_UP = 2_000
        const val ECHOES = 5_000
        const val NEW_WARM_UP = 500
        const val NEW_STREAMS = 2_000

        // Public against internal, about 5 % over what JDK 21 on macOS measured: per echo on one stream 360-488 B on the
        // caller (internal 912), 1 resume, the loop under 1 B; per new-stream echo 2,605-2,742 B (internal 2,392), 3.6-4
        // resumes (internal 5), 34-39 B on the loop. A read is inlined over `QuicReceiveStream.readNext`, so its block is
        // no object and neither its result nor the `ScopedRead` is boxed; a write copies into the stream's send queue.
        // What a new stream adds is the boxed `StreamOpen`, the `CoreBidiStream`, its two direction wrappers and their
        // state cells, and the stream's send queue and read inbox; on the loop, the parked writeAndFinish's wake.
        const val LOOP_GATE = 8
        const val LOOP_GATE_NEW = 44
        const val JDK_LOOP_GATE_ONE = 130
        const val JDK_LOOP_GATE_NEW = 260
        const val PUBLIC_CALLER_GATE_ONE = 520
        const val PUBLIC_CALLER_GATE_NEW = 2_880

        // The alpha01 shapes, a little over what JDK 21 on macOS measured: per echo 463-479 B on the caller, 0 on the
        // loop; per new stream 2,844-2,880 B on the caller (its open and the inbox its first read makes), 34-37 B on the
        // loop; per download read 352-360 B, 0. A new stream's loop bytes: 24 B, the buffer pool's node for the released
        // send buffer, and up to 16 B, kotlinx's `Removed` marker when the loop wakes a writeAndFinish that parked (how
        // often one parks is timing). A spent stream's table entry is reused, so the table does not grow.
        const val ECHO_CALLER_GATE = 520.0
        const val ECHO_LOOP_GATE = 8.0
        const val NEW_CALLER_GATE = 2_950.0
        const val NEW_LOOP_GATE = 44.0
        const val DOWNLOAD_CALLER_GATE = 400.0
        const val DOWNLOAD_LOOP_GATE = 8.0

        fun payloadOf(size: Int) =
            BufferFactory.network().allocate(size).apply {
                repeat(size) { writeByte(it.toByte()) }
                resetForRead()
            }

        fun byteOf(value: Byte) =
            BufferFactory.network().allocate(1).apply {
                writeByte(value)
                resetForRead()
            }
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

/** [native] on the native stack; on the JDK stack (Windows) it also allows the selector's boxed handles. */
private fun stackLoopGate(native: Double): Double = loopGateOfThisStack(native, native + JDK_BOXED_HANDLES_PER_OP)
