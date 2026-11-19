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
import kotlin.time.Duration.Companion.seconds

/**
 * Where a read's answer is paid for: on the caller's thread, whether the read found its bytes in place or waited parked
 * until the loop received them. A read the loop answers allocates nothing there, so how often reads park, which is how
 * fast the host is, adds nothing to the loop's bytes per echo.
 *
 * Both echoes let the loop idle for the same time, once each, so the loop's bytes differ only by who answered the read;
 * an answer's state and result box (48 B per parked echo) do not fit in the margin.
 */
class ReadDeliveryAllocationTests {
    @Test
    fun aReadTheLoopAnswersAllocatesWhatAReadAnsweredInPlaceDoesOnTheLoop() =
        runBlocking {
            skipOnMissingNativeLib(ReadDeliveryAllocationTests::class) {
                withEchoServer { port ->
                    val client = connectCore(port, factory = BufferFactory.network())
                    val dispatcher = ArrayQueueDispatcher(CALLER_THREAD)
                    val done = CompletableFuture<kotlin.Result<Pair<Double, Double>>>()
                    suspend {
                        val stream = client.connection.openStream()
                        val payload = payload()
                        val loop = threadId(client.loop.name)
                        val parked = loopBytesPerEcho(loop) { parkedEcho(stream, payload) }
                        val inPlace = loopBytesPerEcho(loop) { inPlaceEcho(stream, payload) }
                        parked to inPlace
                    }.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
                    val (parked, inPlace) = done.get(120, TimeUnit.SECONDS).getOrThrow()
                    client.connection.close(QuicError.NoError)
                    dispatcher.close()
                    println("[core read delivery allocation] loop %.1f B/echo parked, %.1f B/echo in place".format(parked, inPlace))
                    assertTrue(
                        parked <= inPlace + MORE_AT_MOST,
                        "loop %.1f B/echo when the read waited parked, %.1f when it found its bytes (parked more by at most %d)"
                            .format(parked, inPlace, MORE_AT_MOST),
                    )
                }
            }
        }

    /** The read parks before the write, so the loop answers it when the echo arrives; then the loop idles. */
    private suspend fun parkedEcho(
        stream: QuicByteStream,
        payload: PlatformBuffer,
    ) {
        coroutineScope {
            val read = async(start = CoroutineStart.UNDISPATCHED) { stream.read(10.seconds) }
            payload.position(0)
            stream.write(payload, 10.seconds)
            release(read.await())
        }
        Thread.sleep(IDLE_MILLIS)
    }

    /** The read starts after the echo had time to arrive, so it mostly finds its bytes and answers in place. */
    private suspend fun inPlaceEcho(
        stream: QuicByteStream,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        stream.write(payload, 10.seconds)
        Thread.sleep(IDLE_MILLIS)
        release(stream.read(10.seconds))
    }

    private fun release(result: ReadResult) {
        if (result !is ReadResult.Data) fail("the echo ended early: $result")
        if (result.buffer.remaining() != PAYLOAD) fail("the echo came back in pieces: ${result.buffer.remaining()} bytes")
        result.buffer.freeIfNeeded()
    }

    private suspend fun loopBytesPerEcho(
        loop: Long,
        echo: suspend () -> Unit,
    ): Double {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(WARM_UP) { echo() }
        val before = threads.getThreadAllocatedBytes(loop)
        repeat(ECHOES) { echo() }
        return (threads.getThreadAllocatedBytes(loop) - before).toDouble() / ECHOES
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
        const val CALLER_THREAD = "read-delivery-caller"
        const val PAYLOAD = 64
        const val WARM_UP = 1_000
        const val ECHOES = 2_000
        const val IDLE_MILLIS = 1L
        const val MORE_AT_MOST = 4
    }
}
