package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.JDK_BOXED_HANDLES_PER_OP
import com.ditchoom.socket.quic.core.testing.loopGateOfThisStack
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the public path alpha01 ships costs: the suspend read and the write that copies into the stream's send queue.
 * Bytes per operation on the client's caller and loop threads (exact per-thread counters) for a 64 B echo on one
 * stream, a new stream per echo, and a 16 MiB download read by suspend reads, gated a little over their measurement.
 */
class PublicPathAllocationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** Takes every byte it is lent and counts them; made once, so a read allocates no block. */
    private class Take {
        var bytes = 0L
        val block: suspend (ReadBuffer) -> Unit = { buffer ->
            bytes += buffer.remaining()
            buffer.position(buffer.limit())
        }
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
                    when (val read = receive.read(block = take.block).fold({ Unit }, { it })) {
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

    private suspend fun readOrFail(
        receive: QuicReceiveStream,
        take: Take,
    ) {
        receive.read(block = take.block).getOrElse { fail("the stream ended after ${take.bytes} B: $it") }
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
        const val CLIENT_LOOP = "quic-core-client"
        const val CLIENT_LANE = "quic-core-client-handler"
        const val DOWNLOAD: Byte = 'D'.code.toByte()
        const val ECHO = 64
        const val CHUNK = 1024
        const val TOTAL = 16L * 1_048_576
        const val WARM_BYTES = 2L * 1_048_576
        const val ECHO_WARM_UP = 2_000
        const val ECHOES = 5_000
        const val NEW_WARM_UP = 500
        const val NEW_STREAMS = 2_000

        // Gates, a little over what JDK 21 on macOS measured: per echo 463-479 B on the caller, 0 on the loop; per new stream
        // 2,844-2,880 B on the caller (its open and the inbox its first read makes), 34-37 B on the loop; per download read
        // 352-360 B, 0.
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

/** [native] on the native stack; on the JDK stack (Windows) it also allows the selector's boxed handles. */
private fun stackLoopGate(native: Double): Double = loopGateOfThisStack(native, native + JDK_BOXED_HANDLES_PER_OP)
