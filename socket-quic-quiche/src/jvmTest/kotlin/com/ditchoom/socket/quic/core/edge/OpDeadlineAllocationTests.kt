package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A read that parks with a finite bound allocates what one with no bound does, on the caller's thread and the loop's
 * together: its deadline is a slot the connection lends and takes back, made once, not a node per parking.
 */
class OpDeadlineAllocationTests {
    @Test
    fun aParkedReadWithABoundAllocatesWhatOneWithoutABoundDoes() =
        runBlocking {
            skipOnMissingNativeLib(OpDeadlineAllocationTests::class) {
                withEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val dispatcher = ArrayQueueDispatcher(CALLER_THREAD)
                    val done = CompletableFuture<kotlin.Result<Pair<Double, Double>>>()
                    suspend {
                        val stream = client.connection.openStream()
                        val payload = payload()
                        val threads = listOf(threadId(CALLER_THREAD), threadId(client.loop.name))
                        // Alternating rounds, the least of each: the coroutine machinery's own noise is larger than the margin.
                        var bounded = Double.MAX_VALUE
                        var unbounded = Double.MAX_VALUE
                        repeat(ROUNDS) {
                            bounded = minOf(bounded, bytesPerEcho(threads) { parkedEcho(stream, payload, 10.seconds) })
                            unbounded = minOf(unbounded, bytesPerEcho(threads) { parkedEcho(stream, payload, Duration.INFINITE) })
                        }
                        bounded to unbounded
                    }.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                    val (bounded, unbounded) = done.get(120, TimeUnit.SECONDS).getOrThrow()
                    client.connection.close(QuicError.NoError)
                    dispatcher.close()
                    println("[core op deadline allocation] %.1f B/echo bounded, %.1f B/echo unbounded".format(bounded, unbounded))
                    assertTrue(
                        bounded <= unbounded + MORE_AT_MOST,
                        "a parked read with a bound allocated %.1f B/echo, one without %.1f".format(bounded, unbounded),
                    )
                }
            }
        }

    /** The read parks before the write, so it waits with its bound armed until the echo arrives. */
    private suspend fun parkedEcho(
        stream: QuicByteStream,
        payload: PlatformBuffer,
        bound: Duration,
    ) {
        coroutineScope {
            val read = async(start = CoroutineStart.UNDISPATCHED) { stream.read(bound) }
            payload.position(0)
            stream.write(payload, 10.seconds)
            val result = read.await()
            if (result !is ReadResult.Data) fail("the echo ended early: $result")
            result.buffer.freeIfNeeded()
        }
    }

    private suspend fun bytesPerEcho(
        threads: List<Long>,
        echo: suspend () -> Unit,
    ): Double {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(WARM_UP) { echo() }
        val before = threads.sumOf { bean.getThreadAllocatedBytes(it) }
        repeat(ECHOES) { echo() }
        return (threads.sumOf { bean.getThreadAllocatedBytes(it) } - before).toDouble() / ECHOES
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private companion object {
        const val CALLER_THREAD = "op-deadline-caller"
        const val PAYLOAD = 64
        const val WARM_UP = 2_000
        const val ECHOES = 4_000
        const val MORE_AT_MOST = 8
        const val ROUNDS = 3
    }
}
