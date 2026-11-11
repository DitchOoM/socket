package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.nio.NioClientSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The legacy selector client ([NioClientSocket] non-blocking: Android API 23-25 and anyone who set
 * `useAsyncChannels = false`) used from both directions at once. A read parked on the socket must be
 * woken by the peer's bytes however the writer waits for OP_WRITE in the meantime: reader and writer
 * share one channel, hence one `SelectionKey`, and neither may erase or steal the other's interest or
 * readiness.
 */
class NioFullDuplexTests {
    /** A plain blocking-IO peer: `body` runs on its own thread with the one accepted connection. */
    private class Peer(
        body: (java.net.Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val port = server.localPort
        private val thread =
            thread(isDaemon = true, name = "duplex-peer") {
                try {
                    server.accept().use(body)
                } catch (_: Exception) {
                    // the client went away
                }
            }

        override fun close() {
            server.close()
            thread.join(5_000)
        }
    }

    private suspend fun NioClientSocket.connectTo(port: Int) = open(port, "127.0.0.1")

    private fun payload(
        size: Int,
        fill: Int,
    ): PlatformBuffer =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(fill.toByte()) }
            resetForRead()
        }

    /**
     * Park a read first, issue a write after it is parked, only then let the peer send. The parked
     * read must receive those bytes: the write's OP_WRITE wait must not replace the read's OP_READ.
     */
    @Test
    fun aParkedReadReceivesThePeersBytesAfterAWriteOnTheSameSocket() {
        val clientWrote = CountDownLatch(1)
        Peer { s ->
            s.getInputStream().read() // the client's byte...
            clientWrote.countDown()
            Thread.sleep(300) // ...then the peer answers, so the read has been parked throughout
            s.getOutputStream().write(7)
            s.getOutputStream().flush()
            s.getInputStream().read() // hold the connection until the client closes
        }.use { peer ->
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val socket = NioClientSocket(blocking = false)
                runBlocking { socket.connectTo(peer.port) }
                val read = scope.async { socket.read(30.seconds) }
                Thread.sleep(200) // the read is parked on OP_READ before the write starts
                runBlocking { socket.write(payload(1, 1), 5.seconds) }
                assertTrue(clientWrote.await(5, TimeUnit.SECONDS), "the peer never received the client's byte")
                val result = runBlocking { withTimeoutOrNull(5.seconds) { runCatching { read.await() } } }
                assertNotNull(result, "the parked read did not complete within 5s of the peer's reply")
                val data = result.getOrThrow()
                assertTrue(data is ReadResult.Data && data.buffer.remaining() == 1, "the parked read returned $data, not the peer's byte")
                runBlocking { socket.close() }
            } finally {
                scope.cancel()
            }
        }
    }

    /** Reads and writes interleaved on one socket for many messages: every byte comes back, no empty or timed-out read. */
    @Test
    fun concurrentReadsAndWritesMoveEveryByteBothWays() {
        val messages = 200
        val size = 16 * 1024
        Peer { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        }.use { peer ->
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val socket = NioClientSocket(blocking = false)
                runBlocking { socket.connectTo(peer.port) }
                val total = messages.toLong() * size
                val received =
                    scope.async {
                        var got = 0L
                        while (got < total) {
                            val r = socket.read(10.seconds)
                            assertTrue(r is ReadResult.Data, "read $got/$total ended as $r")
                            val n = r.buffer.remaining()
                            assertTrue(n > 0, "a spurious empty read after $got/$total bytes")
                            got += n
                            (r.buffer as PlatformBuffer).freeNativeMemory()
                        }
                        got
                    }
                val sent =
                    scope.async {
                        repeat(messages) { socket.write(payload(size, it), 10.seconds) }
                    }
                val done = runBlocking { withTimeoutOrNull(60.seconds) { listOf(received, sent).awaitAll() } }
                assertNotNull(done, "the duplex exchange did not finish")
                assertEquals(total, done[0] as Long)
                runBlocking { socket.close() }
            } finally {
                scope.cancel()
            }
        }
    }

    /** Hammer the park-read / write / reply sequence: the shared selected-key set must stay coherent. */
    @Test
    fun repeatedParkReadThenWriteNeverLosesAWake() {
        repeat(50) { round ->
            Peer { s ->
                s.getInputStream().read()
                s.getOutputStream().write(round)
                s.getOutputStream().flush()
                s.getInputStream().read()
            }.use { peer ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    val socket = NioClientSocket(blocking = false)
                    runBlocking { socket.connectTo(peer.port) }
                    val read = scope.async { socket.read(30.seconds) }
                    Thread.sleep(20.milliseconds.inWholeMilliseconds)
                    runBlocking { socket.write(payload(1, 1), 5.seconds) }
                    val result = runBlocking { withTimeoutOrNull(5.seconds) { runCatching { read.await() } } }
                    assertNotNull(result, "round $round: the parked read was never woken")
                    val data = result.getOrThrow()
                    assertTrue(data is ReadResult.Data && data.buffer.remaining() == 1, "round $round: read returned $data")
                    runBlocking { socket.close() }
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
