package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.loopGateOfThisStack
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.time.Instant
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
 * What the loop allocates per 64-byte echo when it runs every write and read itself. A caller on a main dispatcher hands
 * each call to the loop, which is also where a call lands whenever its caller finds the loop inside the connection: how
 * often that happens depends on the host's speed, so the loop's side of a call must allocate nothing for the loop's
 * bytes per echo to hold on a slow host as on a fast one.
 */
class LoopRunOpsAllocationTests {
    @Test
    fun anEchoWhoseCallsTheLoopRunsAllocatesNothingOnTheLoop() =
        runBlocking {
            skipOnMissingNativeLib(LoopRunOpsAllocationTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val main = ArrayQueueMain()
                    val payload =
                        BufferFactory.network().allocate(PAYLOAD).apply {
                            repeat(PAYLOAD) { writeByte(it.toByte()) }
                            resetForRead()
                        }
                    val done = CompletableFuture<Result<Pair<Double, String>>>()
                    suspend { measure(client, payload) }.startCoroutine(Continuation(main + Job()) { done.complete(it) })
                    val (loop, where) = done.get(120, TimeUnit.SECONDS).getOrThrow()
                    println("[core loop-run allocation] loop %.2f B/echo\n%s".format(loop, where))
                    client.connection.close(QuicError.NoError)
                    main.close()
                    val gate = loopGateOfThisStack(LOOP_GATE, JDK_LOOP_GATE)
                    assertTrue(
                        loop <= gate,
                        "the loop allocated %.2f B per echo it ran (gate %.1f), sampled from:\n%s".format(loop, gate, where),
                    )
                }
            }
        }

    private suspend fun measure(
        client: CoreClient,
        payload: PlatformBuffer,
    ): Pair<Double, String> {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val stream = client.connection.openStream()
        repeat(WARM_UP) { echo(stream, payload) }
        val loopId =
            Thread
                .getAllStackTraces()
                .keys
                .first { it.name == client.loop.name }
                // id, not threadId() (JDK 19): the allocation lanes include JDK 17.
                .id
        val handedBefore = client.conn.counts.handedToLoop
        val recording = jdk.jfr.Recording()
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
        recording.start()
        val from = Instant.now()
        val before = threads.getThreadAllocatedBytes(loopId)
        repeat(MEASURED) { echo(stream, payload) }
        val loop = (threads.getThreadAllocatedBytes(loopId) - before).toDouble() / MEASURED
        recording.stop()
        val handed = client.conn.counts.handedToLoop - handedBefore
        if (handed < MEASURED) fail("the loop must run the calls: $handed handed to it in $MEASURED echoes")
        return loop to sites(recording, from, client.loop.name)
    }

    /** The loop's allocation sites since [from], in bytes per echo. */
    private fun sites(
        recording: jdk.jfr.Recording,
        from: Instant,
        loopName: String,
    ): String {
        val dump =
            java.nio.file.Files
                .createTempFile("core-loop-run", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump) { it == loopName }
            .filter { it.startTime >= from }
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
            .take(15)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

    /** A main dispatcher on a thread of its own, so every call it makes is handed to the loop. */
    private class ArrayQueueMain : MainCoroutineDispatcher() {
        private val inner = ArrayQueueDispatcher(MAIN_THREAD)
        override val immediate: MainCoroutineDispatcher get() = this

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = inner.dispatch(context, block)

        fun close() = inner.close()
    }

    private companion object {
        const val MAIN_THREAD = "core-loop-run-main"
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000

        /** JIT noise; the loop's own share of a call is meant to be zero. */
        const val LOOP_GATE = 1.0

        /** The JDK stack's boxed descriptor per selector wake: 31.9 B/echo measured on Windows (run 44471687771). */
        const val JDK_LOOP_GATE = 64.0
    }
}

/** Writes [payload] on [stream] and reads it back, releasing each result buffer. */
private suspend fun echo(
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
