package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.data.readBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Who owns an outstanding Network.framework receive or send: the connection, not the caller that
 * happened to be waiting on it. Network.framework has no per-operation cancel, so a caller that
 * is cancelled leaves its operation running, and the operation's result must still land somewhere
 * the next caller can reach.
 */
class AppleTcpInFlightOwnershipTests {
    private fun bytesOf(text: String): com.ditchoom.buffer.ReadBuffer {
        val b = BufferFactory.Default.allocate(text.length)
        b.writeBytes(text.encodeToByteArray())
        b.resetForRead()
        return b
    }

    private fun com.ditchoom.buffer.ReadBuffer.text(): String = readString(remaining())

    /** Reads until [wanted] bytes arrive or a read times out. */
    private suspend fun ClientSocket.readUpTo(
        wanted: Int,
        deadline: Duration = 2.seconds,
    ): String {
        val sb = StringBuilder()
        try {
            while (sb.length < wanted) sb.append(readBuffer(deadline).text())
        } catch (_: SocketTimeoutException) {
        }
        return sb.toString()
    }

    /** S1: a read cancelled from outside must not strand the bytes its native receive later collects. */
    @Test
    fun cancelledReadDoesNotLoseTheBytesItsReceiveCollects() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val server = ServerSocket.allocate()
            val flow = server.bind()
            val accepted = CompletableDeferred<ClientSocket>()
            val serverJob = launch(Dispatchers.Default) { flow.collect { accepted.complete(it) } }
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), "127.0.0.1")
            val peer = accepted.await()

            val reader = launch(Dispatchers.Default) { client.readBuffer(Duration.INFINITE) }
            delay(300.milliseconds) // the receive is posted and waiting
            reader.cancelAndJoin()

            peer.write(bytesOf("A"), 5.seconds)
            delay(300.milliseconds) // "A" reaches the receive the cancelled reader left outstanding
            peer.write(bytesOf("B"), 5.seconds)
            delay(300.milliseconds)

            assertEquals("AB", client.readUpTo(2), "bytes collected by the cancelled reader's receive were lost")
            client.close()
            peer.close()
            server.close()
            serverJob.cancelAndJoin()
        }

    /** S2: a write cancelled from outside returned the buffer; the late completion must leave it alone. */
    @Test
    fun cancelledWriteDoesNotMoveTheCallersBufferPosition() =
        runTestNoTimeSkipping(timeout = 120.seconds) {
            val server = ServerSocket.allocate()
            val flow = server.bind()
            val accepted = CompletableDeferred<ClientSocket>()
            val serverJob = launch(Dispatchers.Default) { flow.collect { accepted.complete(it) } }
            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), "127.0.0.1")
            val peer = accepted.await()

            val size = 64 * 1024 * 1024
            val big = BufferFactory.Default.allocate(size)
            big.position(size)
            big.resetForRead()
            val writer = launch(Dispatchers.Default) { client.write(big, Duration.INFINITE) }
            delay(500.milliseconds) // the peer reads nothing: the send is parked on back-pressure
            assertTrue(writer.isActive, "the write finished before the peer read anything; the test cannot park a send")
            writer.cancelAndJoin()
            assertEquals(0, big.position(), "the cancelled write moved the position before any completion")

            // The peer drains; every queued send completes now, on a dispatch thread.
            var drained = 0L
            try {
                while (drained < size) drained += peer.readBuffer(2.seconds).remaining()
            } catch (_: SocketTimeoutException) {
            }
            delay(500.milliseconds)
            assertEquals(0, big.position(), "a late send completion advanced a buffer the caller owns again (drained=$drained)")
            client.close()
            peer.close()
            server.close()
            serverJob.cancelAndJoin()
        }

    /** U1: `write(...); close()` must deliver every byte whose write returned. */
    @Test
    fun writeThenCloseDeliversEveryByte() =
        runTestNoTimeSkipping(timeout = 120.seconds) {
            repeat(5) { round ->
                val server = ServerSocket.allocate()
                val flow = server.bind()
                val total = CompletableDeferred<Long>()
                val serverJob =
                    launch(Dispatchers.Default) {
                        flow.collect { peer ->
                            launch(Dispatchers.Default) {
                                var n = 0L
                                delay(200.milliseconds) // the bytes sit queued in the stack when the client closes
                                try {
                                    while (true) n += peer.readBuffer(10.seconds).remaining()
                                } catch (_: SocketException) {
                                }
                                total.complete(n)
                                peer.close()
                            }
                        }
                    }
                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                client.open(server.port(), "127.0.0.1")
                val size = 16 * 1024 * 1024
                val buf = BufferFactory.Default.allocate(size)
                buf.position(size)
                buf.resetForRead()
                client.write(buf, 30.seconds)
                client.close()
                assertEquals(size.toLong(), total.await(), "round $round: bytes lost between write() returning and close()")
                server.close()
                serverJob.cancelAndJoin()
            }
        }
}
