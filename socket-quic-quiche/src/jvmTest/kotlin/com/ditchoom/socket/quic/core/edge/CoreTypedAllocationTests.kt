package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.sched.sendRuleOfThisRuntime
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.loopGateOfThisStack
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
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

/** A message of one 8-byte field: the least a value can force on a codec. */
private class Tick(
    val n: Long,
)

/** A 4-byte count of the bytes after it, then the field. */
private object TickCodec : Codec<Tick> {
    private const val HEADER = 4

    override fun encode(
        buffer: WriteBuffer,
        value: Tick,
        context: EncodeContext,
    ) {
        buffer.writeInt(Long.SIZE_BYTES)
        buffer.writeLong(value.n)
    }

    override fun wireSize(
        value: Tick,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(HEADER + Long.SIZE_BYTES)

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Tick {
        buffer.readInt()
        return Tick(buffer.readLong())
    }

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult =
        if (stream.available() < baseOffset + HEADER) PeekResult.NeedsMoreData else PeekResult.Complete(HEADER + stream.peekInt(baseOffset))
}

/**
 * What a typed echo allocates per message on the client, on the loop's thread and the caller's, with the JVM's per-thread
 * counter after warm-up, as [CoreQuicAllocationTests] measures a byte-stream echo. The caller's total is split into what
 * the codec and its value force (encoding, frame detection and decoding the same message with no transport, measured the
 * same way) and what is left, which is the transport's: the suspend calls of [TypedQuicStream.send] and
 * [TypedQuicStream.receive], the [ScopedReadResult] that carries a message, and the continuation of a call that has to wait.
 * A receive of a frame already buffered allocates no continuation, nor, where callers send in place
 * ([SendRule.InPlace]), does a send that quiche takes at once; under [SendRule.ByLoop] every send waits for the loop.
 */
class CoreTypedAllocationTests {
    @Test
    fun aPingPongEchoAllocatesTheCodecsAndTheWaitsContinuations() =
        measure("ping-pong", batch = 1, callerGate = PING_PONG_GATE, sends = sendRuleOfThisRuntime)

    @Test
    fun anEchoOfFramesAlreadyBufferedAllocatesLittleBeyondTheCodecWhereCallersSendInPlace() =
        measure("pipelined in place", batch = BATCH, callerGate = pipelinedGateOf(SendRule.InPlace), sends = SendRule.InPlace)

    @Test
    fun anEchoOfFramesAlreadyBufferedAllocatesLittleBeyondTheCodecWhereTheLoopSendsEverything() =
        measure("pipelined by loop", batch = BATCH, callerGate = pipelinedGateOf(SendRule.ByLoop), sends = SendRule.ByLoop)

    private fun pipelinedGateOf(rule: SendRule): Int =
        when (rule) {
            is SendRule.InPlace -> PIPELINED_GATE
            is SendRule.ByLoop -> PIPELINED_BY_LOOP_GATE
        }

    private fun measure(
        name: String,
        batch: Int,
        callerGate: Int,
        sends: SendRule,
    ) = runBlocking {
        withDescriptorsAbove127 {
            skipOnMissingNativeLib(CoreTypedAllocationTests::class) {
                withCoreServer(sends = sends, onConnection = { connection ->
                    val stream = connection.acceptTypedStream(TickCodec)
                    while (true) {
                        when (val read = stream.receive()) {
                            is ScopedReadResult.Data -> stream.send(read.value)
                            else -> break
                        }
                    }
                    stream.close()
                }) { server ->
                    val loops = LoopSet(1, sends = sends)
                    val client = connectCore(server.port, loops = loops, factory = BufferFactory.network())
                    val dispatcher = TypedCallerDispatcher(CALLER_THREAD)
                    val done = CompletableFuture<kotlin.Result<Report>>()
                    suspend { run(client, batch) }.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                    val report = done.get(120, TimeUnit.SECONDS).getOrThrow()
                    println(
                        "[core typed allocation: $name] loop %.1f B/echo, caller total %.1f B/echo, codec and value %.1f B/echo, transport %.1f B/echo\n%s"
                            .format(report.loop, report.caller, report.codec, report.caller - report.codec, report.where),
                    )
                    client.connection.close(QuicError.NoError)
                    dispatcher.close()
                    loops.close()
                    val loopGate = loopGateOfThisStack(LOOP_GATE, JDK_LOOP_GATE)
                    assertTrue(
                        report.loop <= loopGate && report.caller <= callerGate,
                        "$name: loop %.1f B (gate %d), caller total %.1f B (gate %d; the codec and its value alone measure %.1f, %.1f of it the transport), sampled from:\n%s"
                            .format(
                                report.loop,
                                loopGate,
                                report.caller,
                                callerGate,
                                report.codec,
                                report.caller - report.codec,
                                report.where,
                            ),
                    )
                }
            }
        }
    }

    /** [batch] messages are sent before their echoes are read: 1 is ping-pong, more finds frames already buffered. */
    private suspend fun run(
        client: CoreClient,
        batch: Int,
    ): Report {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val stream = client.connection.openTypedStream(TickCodec)
        for (round in 0 until WARM_UP / batch) {
            for (i in 0 until batch) stream.send(Tick(i.toLong()))
            for (i in 0 until batch) check((stream.receive() as ScopedReadResult.Data<Tick>).value.n == i.toLong())
        }
        val codecBytes = codecOnly(threads)
        val loopId = threadId(client.loop.name)
        val callerId = Thread.currentThread().id
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
        recording.start()
        val loopBefore = threads.getThreadAllocatedBytes(loopId)
        val callerBefore = threads.getThreadAllocatedBytes(callerId)
        val from = Instant.now()
        for (round in 0 until MEASURED / batch) {
            for (i in 0 until batch) stream.send(Tick(i.toLong()))
            for (i in 0 until batch) check((stream.receive() as ScopedReadResult.Data<Tick>).value.n == i.toLong())
        }
        val callerBytes = (threads.getThreadAllocatedBytes(callerId) - callerBefore).toDouble() / MEASURED
        val loopBytes = (threads.getThreadAllocatedBytes(loopId) - loopBefore).toDouble() / MEASURED
        recording.stop()
        stream.close()
        return Report(loopBytes, callerBytes, codecBytes, where(recording, from))
    }

    /** What encoding, sizing and decoding one message allocates when nothing carries it anywhere. */
    private fun codecOnly(threads: com.sun.management.ThreadMXBean): Double {
        val scratch = FrameScratch(BufferFactory.network(), BOUND)
        val inbox = FrameAccumulator(BufferFactory.network(), BOUND)
        val carrier = BufferFactory.network().allocate(HEADER_PLUS_FIELD)

        fun once(n: Long) {
            scratch.encode(TickCodec, Tick(n), EncodeContext.Empty)
            carrier.resetForWrite()
            for (i in 0 until scratch.length) carrier.writeByte(0)
            carrier.resetForRead()
            carrier.position(0)
            inbox.append(carrier)
            TickCodec.peekFrameSize(inbox)
            inbox.decode(TickCodec, DecodeContext.Empty, HEADER_PLUS_FIELD)
        }
        for (n in 0 until WARM_UP) once(n.toLong())
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        for (n in 0 until MEASURED) once(n.toLong())
        val bytes = (threads.getThreadAllocatedBytes(id) - before).toDouble() / MEASURED
        scratch.freeStorage()
        inbox.freeStorage()
        carrier.freeNativeMemory()
        return bytes
    }

    private class Report(
        val loop: Double,
        val caller: Double,
        val codec: Double,
        val where: String,
    )

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private fun where(
        recording: jdk.jfr.Recording,
        from: Instant,
    ): String {
        val dump =
            java.nio.file.Files
                .createTempFile("core-typed", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump) { it.startsWith(LOOP_THREAD) || it == CALLER_THREAD }
            .filter { it.startTime >= from }
            .forEach { e ->
                val frames =
                    e.stackTrace
                        ?.frames
                        .orEmpty()
                        .take(6)
                        .joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                sites.merge("[${e.thread?.javaName}] ${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        return sites.entries
            .sortedByDescending { it.value }
            .take(20)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

    private companion object {
        const val CALLER_THREAD = "core-typed-alloc-caller"
        const val HEADER_PLUS_FIELD = 12
        const val BOUND = 4_096
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
        const val LOOP_GATE = 16

        /** The JDK stack's boxed descriptor per selector wake: 19.2 B/echo measured on Windows (run 44471687771). */
        const val JDK_LOOP_GATE = 48
        const val PING_PONG_GATE = 520
        const val PIPELINED_GATE = 270

        /** Each send's wait: its continuation, its dispatch and its cancellation handler, 346 B/echo in all on a Mac. */
        const val PIPELINED_BY_LOOP_GATE = 400
        const val BATCH = 32
    }
}

/** Runs a caller's tasks on one thread of its own, from a bounded array queue so a hand-off allocates no node. */
private class TypedCallerDispatcher(
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
