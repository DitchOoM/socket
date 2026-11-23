package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.demux.SERVER_LOOP_THREAD
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.loopGateOfThisStack
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What a server allocates per stream a handler handles: on its pool threads, which make the handler's coroutine and
 * its stream, and on its loop, which only resumes the acceptor with the stream and the handler with its read.
 *
 * Measured on JDK 21, coroutine debug mode off (the task turns it off): a handler that closes its stream costs the pool
 * about 1,390 B and one that reads once first about 1,840 B; most of it is coroutine machinery (the handler's launch and
 * context, each read's cancellation hook, suspend frames), still to trim. The hand-off allocates nothing: a step is a
 * ring slot, a ready lane a ring push. The loop's share is the incoming-stream record and its queue's segments, about
 * 35 B per stream, and nothing per echo to a pool handler. Per connection offered, the loop makes the connection's lane,
 * reported, not gated: it sums JFR samples, and a sample's weight is all the thread allocated since the last one, so
 * one sample landing in the pool's frames can count kilobytes of the handshake (667 B to 13 KB across runners).
 * The other gates read the threads' exact allocation counters, which vary by under 1 %; each sits a little over its measurement.
 */
class HandlerAllocationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aHandlerThatClosesItsStreamCostsLittlePerStream() = measure("closes", POOL_GATE_CLOSES) { stream -> stream.close() }

    @Test
    fun aHandlerThatReadsOnceThenClosesCostsLittleMorePerStream() =
        measure("reads once", POOL_GATE_READS) { stream ->
            when (val read = stream.read(10.seconds)) {
                is ReadResult.Data -> read.buffer.freeIfNeeded()
                ReadResult.End, ReadResult.Reset -> Unit
            }
            stream.close()
        }

    /** The same streams read by a collector of the connection's streams off the pool: what the loop pays without it. */
    @Test
    fun theLoopsShareOfAStreamIsTheSameWithoutThePool() =
        measure("closes, no pool", Int.MAX_VALUE, loopGate = Int.MAX_VALUE, onConnection = { connection, handled ->
            connection.streams().collect { stream ->
                launch(Dispatchers.Default) {
                    stream.close()
                    handled.incrementAndGet()
                }
            }
        })

    /**
     * What the server's loop allocates in the handler code for each connection it offers: the connection's lane, made
     * when its handshake completes, and the offer's step. Sampled by JFR on the loop thread, from the frames of the pool
     * and seat classes only, as the rest of a connection's setup is not the pool's. Printed only: see the class notes.
     */
    @Test
    fun whatTheLoopAllocatesInThePoolPerOfferedConnectionIsReported() =
        runBlocking {
            skipOnMissingNativeLib(HandlerAllocationTests::class) {
                // The helper takes every accepted connection, so the test waits on its offer, not on a second accept.
                val offered = Channel<Unit>(Channel.UNLIMITED)
                withCoreServer(onConnection = { offered.trySend(Unit) }) { server ->
                    repeat(WARM_CONNECTIONS) {
                        connectCore(server.port, loops = loops).connection.close(QuicError.NoError)
                        offered.receive()
                    }
                    val recording = jdk.jfr.Recording()
                    recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
                    recording.start()
                    repeat(CONNECTIONS) {
                        connectCore(server.port, loops = loops).connection.close(QuicError.NoError)
                        offered.receive()
                    }
                    recording.stop()
                    val dump =
                        java.nio.file.Files
                            .createTempFile("handler-offer", ".jfr")
                    recording.dump(dump)
                    val bytes =
                        allocationSamples(dump) { it == SERVER_LOOP_THREAD }
                            .filter { e ->
                                e.stackTrace
                                    ?.frames
                                    .orEmpty()
                                    .any { isPoolFrame(it.method.type.name) }
                            }.sumOf { it.getLong("weight") }
                    java.nio.file.Files
                        .delete(dump)
                    val perConnection = bytes.toDouble() / CONNECTIONS
                    println("[handler allocation] offer: server loop %.0f B/connection in the pool's code".format(perConnection))
                }
            }
        }

    private fun isPoolFrame(type: String): Boolean =
        type.startsWith("com.ditchoom.socket.quic.core.sched.") &&
            POOL_TYPES.any { type.removePrefix("com.ditchoom.socket.quic.core.sched.").startsWith(it) } ||
            type.startsWith("com.ditchoom.socket.quic.core.edge.HandlerSeat")

    @Test
    fun theLoopAllocatesNothingToResumeAHandlerPerEcho() =
        runBlocking {
            skipOnMissingNativeLib(HandlerAllocationTests::class) {
                withCoreServer(onConnection = { connection ->
                    connection.handleStreams { stream ->
                        while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedReadResult.Data<*>) Unit
                        stream.close()
                    }
                }) { server ->
                    val client = connectCore(server.port, loops = loops, factory = BufferFactory.network())
                    val stream = client.connection.openStream()
                    val payload =
                        BufferFactory.network().allocate(ECHO_BYTES).apply {
                            repeat(ECHO_BYTES) { writeByte(it.toByte()) }
                            resetForRead()
                        }

                    suspend fun echo() {
                        payload.position(0)
                        stream.write(payload, 10.seconds)
                        var received = 0
                        while (received < ECHO_BYTES) {
                            val read = stream.read(10.seconds)
                            if (read !is ReadResult.Data) fail("the echo ended early: $read")
                            received += read.buffer.remaining()
                            read.buffer.freeIfNeeded()
                        }
                    }
                    repeat(WARM_UP) { echo() }
                    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                    val loop =
                        Thread
                            .getAllStackTraces()
                            .keys
                            .first { it.name == SERVER_LOOP_THREAD }
                            .id
                    val before = threads.getThreadAllocatedBytes(loop)
                    repeat(ECHOES) { echo() }
                    val perEcho = (threads.getThreadAllocatedBytes(loop) - before).toDouble() / ECHOES
                    println("[handler allocation] echo: server loop %.2f B/echo".format(perEcho))
                    client.connection.close(QuicError.NoError)
                    assertTrue(perEcho < LOOP_GATE, "the server loop allocated %.2f B per echo to a pool handler".format(perEcho))
                }
            }
        }

    private fun measure(
        what: String,
        poolGate: Int,
        handler: suspend (QuicByteStream) -> Unit,
    ) = measure(what, poolGate, onConnection = { connection, handled ->
        connection.handleStreams {
            handler(it)
            handled.incrementAndGet()
        }
    })

    private fun measure(
        what: String,
        poolGate: Int,
        loopGate: Int = LOOP_STREAM_GATE,
        onConnection: suspend CoroutineScope.(CoreConnection, AtomicLong) -> Unit,
    ) = runBlocking {
        skipOnMissingNativeLib(HandlerAllocationTests::class) {
            val handled = AtomicLong()
            withCoreServer(onConnection = { connection -> onConnection(connection, handled) }) { server ->
                val client = connectCore(server.port, loops = loops, factory = BufferFactory.network())
                val payload = BufferFactory.network().allocate(1).apply { writeByte(1) }

                suspend fun oneStream() {
                    val target = handled.get() + 1
                    val stream = client.connection.openStream()
                    payload.position(0)
                    stream.write(payload, 10.seconds)
                    stream.close()
                    when (val awaited = awaitCount(handled, target, HANDLER_RETURNS_WITHIN)) {
                        is CountAwaited.Reached -> Unit
                        is CountAwaited.NotReached ->
                            fail("the handler of stream ${stream.streamId} did not return within ${awaited.waited}")
                    }
                }
                repeat(WARM_UP) { oneStream() }
                val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val all = Thread.getAllStackTraces().keys
                val pool = all.filter { it.name.startsWith(SERVER_HANDLER_THREAD) }.map { it.id }
                val loop = all.first { it.name == SERVER_LOOP_THREAD }.id

                fun poolBytes() = pool.sumOf { threads.getThreadAllocatedBytes(it) }
                val recording = jdk.jfr.Recording()
                recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
                recording.start()
                val poolBefore = poolBytes()
                val loopBefore = threads.getThreadAllocatedBytes(loop)
                repeat(MEASURED) { oneStream() }
                val perStream = (poolBytes() - poolBefore).toDouble() / MEASURED
                val loopPerStream = (threads.getThreadAllocatedBytes(loop) - loopBefore).toDouble() / MEASURED
                recording.stop()
                println("[handler allocation] $what: pool %.0f B/stream, loop %.0f B/stream".format(perStream, loopPerStream))
                println("pool:\n" + sites(recording) { it.startsWith(SERVER_HANDLER_THREAD) })
                println("loop:\n" + sites(recording) { it == SERVER_LOOP_THREAD })
                client.connection.close(QuicError.NoError)
                assertTrue(
                    perStream <= poolGate,
                    "$what: the pool allocated %.0f B per handled stream (gate %d)".format(perStream, poolGate),
                )
                assertTrue(loopPerStream <= loopGate, "$what: the loop allocated %.0f B per handled stream".format(loopPerStream))
            }
        }
    }

    /** The pool threads' top allocation sites, in bytes per stream. */
    private fun sites(
        recording: jdk.jfr.Recording,
        onThread: (String) -> Boolean,
    ): String {
        val dump =
            java.nio.file.Files
                .createTempFile("handler-allocation", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump, onThread).forEach { e ->
            val frames =
                e.stackTrace
                    ?.frames
                    .orEmpty()
                    .take(5)
                    .joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
            sites.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
        }
        java.nio.file.Files
            .delete(dump)
        return sites.entries
            .sortedByDescending { it.value }
            .take(25)
            .joinToString("\n") { "  ${it.value / MEASURED} B/stream ${it.key}" }
    }

    private companion object {
        const val WARM_CONNECTIONS = 50
        val POOL_TYPES =
            listOf("HandlerPool", "HandlerLane", "StepRing", "LaneRing", "LoopFeed", "HandlerOutbox", "PoolReports", "PoolWorker")
        const val WARM_UP = 2_000
        const val MEASURED = 5_000
        const val POOL_GATE_CLOSES = 1_450
        const val POOL_GATE_READS = 1_900
        const val CONNECTIONS = 300
        const val ECHO_BYTES = 64
        const val ECHOES = 20_000

        /** Past the handler's own 10 s read and write deadlines: a handler stuck on either has failed by then. */
        val HANDLER_RETURNS_WITHIN = 15.seconds

        /**
         * The loop's gates, per stream and per echo. On the JDK stack (Windows) its selector boxes each ready socket
         * handle, 16 B a wake inside NIO: 59 B per stream and 18.7 B per echo measured there (run 44970928619).
         */
        val LOOP_STREAM_GATE = loopGateOfThisStack(40, 96)
        val LOOP_GATE = loopGateOfThisStack(1.0, 40.0)
    }
}
