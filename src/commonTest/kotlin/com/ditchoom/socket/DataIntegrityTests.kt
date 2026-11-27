package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.testkit.scaled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Tests for data integrity and large data transfers.
 */
class DataIntegrityTests {
    @Test
    fun binaryDataTransfer() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val dataReceived = Mutex(locked = true)

            // Generate binary data with all byte values
            val binaryData = ByteArray(256) { it.toByte() }
            var receivedData: ByteArray? = null

            val accepts = AcceptLog()
            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.serveEach(accepts) { client ->
                        val buffer = client.readBuffer(5.seconds)
                        receivedData = buffer.readByteArray(buffer.remaining())
                        dataReceived.unlock()
                    }
                }

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), hostname = "127.0.0.1")
            accepts.client(client)

            val sendBuffer = BufferFactory.Default.allocate(binaryData.size)
            sendBuffer.writeBytes(binaryData)
            sendBuffer.resetForRead()
            client.write(sendBuffer, 5.seconds)
            accepts.note("client wrote ${binaryData.size} bytes")
            client.close()
            accepts.note("client closed")

            dataReceived.lockWithTimeout()
            assertContentEquals(binaryData, receivedData, "Binary data should be transferred intact\n${accepts.describe()}")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun largeDataTransfer_64KB() =
        runTestNoTimeSkipping {
            testLargeDataTransfer(64 * 1024) // 64 KB
        }

    @Test
    fun largeDataTransfer_256KB() =
        runTestNoTimeSkipping {
            testLargeDataTransfer(256 * 1024) // 256 KB
        }

    @Test
    fun largeDataTransfer_1MB() =
        runTestNoTimeSkipping {
            testLargeDataTransfer(1024 * 1024) // 1 MB
        }

    private suspend fun testLargeDataTransfer(size: Int) {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val transferComplete = Mutex(locked = true)

        // Generate random data for this test
        val random = Random(42) // Fixed seed for reproducibility
        val testData = ByteArray(size) { random.nextInt().toByte() }
        var receivedSize = 0
        var checksumMatch = false
        val accepts = AcceptLog()

        val serverJob =
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                serverFlow.serveEach(accepts) { client ->
                    val receivedBytes = mutableListOf<Byte>()

                    // Read until we have all the data
                    while (receivedBytes.size < size) {
                        try {
                            val buffer = client.readBuffer(10.seconds)
                            val chunk = buffer.readByteArray(buffer.remaining())
                            receivedBytes.addAll(chunk.toList())
                        } catch (e: SocketException) {
                            // Connection closed or other error
                            accepts.note("server read ended after ${receivedBytes.size} bytes: ${e::class.simpleName}")
                            break
                        }
                    }

                    receivedSize = receivedBytes.size
                    if (receivedSize == size) {
                        val receivedArray = receivedBytes.toByteArray()
                        checksumMatch = testData.contentEquals(receivedArray)
                    }

                    transferComplete.unlock()
                }
            }

        val client = ClientSocket.allocate(TransportConfig(connectTimeout = 10.seconds))
        client.open(server.port(), hostname = "127.0.0.1")
        accepts.client(client)

        // Send data in chunks
        var offset = 0
        val chunkSize = 8192
        while (offset < size) {
            val remaining = minOf(chunkSize, size - offset)
            val chunk = BufferFactory.Default.allocate(remaining)
            chunk.writeBytes(testData, offset, remaining)
            chunk.resetForRead()

            var written = 0
            while (written < remaining) {
                val w = client.write(chunk, 10.seconds).count
                if (w <= 0) break
                written += w
            }
            offset += remaining
        }
        accepts.note("client wrote $size bytes")
        client.close()
        accepts.note("client closed")

        transferComplete.lockWithTimeout()
        assertEquals(size, receivedSize, "Should receive all $size bytes\n${accepts.describe()}")
        assertTrue(checksumMatch, "Data checksum should match for $size byte transfer\n${accepts.describe()}")

        server.close()
        serverJob.cancel()
    }

    @Test
    fun bidirectionalDataTransfer() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val testComplete = Mutex(locked = true)

            val clientToServerData = "Hello from client! " + "X".repeat(1000)
            val serverToClientData = "Hello from server! " + "Y".repeat(1000)
            var serverReceivedCorrect = false
            var clientReceivedCorrect = false

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        client.closeAfter {
                            // Read from client
                            val received = client.readString(deadline = 5.seconds)
                            serverReceivedCorrect = (received == clientToServerData)

                            // Send to client
                            client.writeString(serverToClientData)
                        }
                        testComplete.unlock()
                    }
                }

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), hostname = "127.0.0.1")

            // Send to server
            client.writeString(clientToServerData)

            // Read from server
            val received = client.readString(deadline = 5.seconds)
            clientReceivedCorrect = (received == serverToClientData)

            client.close()
            testComplete.lockWithTimeout()

            assertTrue(serverReceivedCorrect, "Server should receive correct data from client")
            assertTrue(clientReceivedCorrect, "Client should receive correct data from server")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun multipleSmallWrites() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val dataReceived = Mutex(locked = true)

            val messageCount = 100
            var receivedData = ""

            // Build expected data first so we know when we're done
            val expectedData = StringBuilder()
            repeat(messageCount) { i ->
                expectedData.append("msg$i;")
            }
            val expectedString = expectedData.toString()

            val accepts = AcceptLog()
            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.serveEach(accepts) { client ->
                        val sb = StringBuilder()
                        // Read until we have all the data or connection closes
                        // TCP may coalesce multiple writes into single reads
                        while (sb.length < expectedString.length) {
                            try {
                                val chunk = client.readString(deadline = 1.seconds)
                                sb.append(chunk)
                            } catch (e: SocketClosedException) {
                                // Connection closed, we have all the data
                                accepts.note("server read ended after ${sb.length} chars: ${e::class.simpleName}")
                                break
                            }
                        }
                        receivedData = sb.toString()
                        dataReceived.unlock()
                    }
                }

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), hostname = "127.0.0.1")
            accepts.client(client)

            // Send many small messages
            repeat(messageCount) { i ->
                val msg = "msg$i;"
                client.writeString(msg)
            }
            accepts.note("client wrote $messageCount messages")
            client.close()
            accepts.note("client closed")

            dataReceived.lockWithTimeout()
            assertEquals(expectedString, receivedData, "All small messages should be received in order\n${accepts.describe()}")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun partialReadHandling() =
        runTestNoTimeSkipping {
            readPacedParts(pause = 50.milliseconds, readDeadline = 1.seconds)
        }

    /**
     * The peer pauses longer than each read's deadline, so reads expire between parts — what a runner
     * that stops scheduling the test process does to [partialReadHandling]. An expired read deadline
     * abandons only the wait: the parts still arrive whole and in order.
     */
    @Test
    fun partsArrivingAfterAReadDeadlineAreStillRead() =
        runTestNoTimeSkipping {
            readPacedParts(pause = 400.milliseconds, readDeadline = 150.milliseconds)
        }

    /** A server writes [PACED_PARTS] one at a time, [pause] apart, then closes; the client reads them all. */
    private suspend fun CoroutineScope.readPacedParts(
        pause: Duration,
        readDeadline: Duration,
    ) {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val clientConnected = Mutex(locked = true)

        val serverJob =
            launch(Dispatchers.Default) {
                serverFlow.collect { client ->
                    // The reader can hold all the parts before the last write returns here, and the
                    // test then cancels this job inside it: the close must not depend on that write.
                    try {
                        clientConnected.unlock()
                        PACED_PARTS.forEachIndexed { index, part ->
                            if (index > 0) delay(pause)
                            client.writeString(part)
                        }
                    } finally {
                        client.close()
                    }
                }
            }

        val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
        client.open(server.port(), hostname = "127.0.0.1")
        clientConnected.lockWithTimeout()

        val expected = PACED_PARTS.joinToString("")
        val received = client.readUntil(expected.length, readDeadline, budget = 10.seconds.scaled)
        assertEquals(expected, received.text, "All parts should be received; $received")

        client.close()
        server.close()
        serverJob.cancel()
    }

    /** What [readUntil] collected, and how many of its reads ended at their deadline. */
    private class Received(
        val text: String,
        val expiredDeadlines: Int,
        val elapsed: Duration,
    ) {
        override fun toString() = "received '$text' in $elapsed, $expiredDeadlines read deadline(s) expired"
    }

    /**
     * Reads until [length] characters have arrived, the stream ends, or [budget] is spent. A read whose
     * [readDeadline] expires is not a failure: the next read collects the same bytes. [budget] is
     * checked between reads rather than wrapped around them, so it can never cancel a read mid-flight.
     */
    private suspend fun ClientSocket.readUntil(
        length: Int,
        readDeadline: Duration,
        budget: Duration,
    ): Received {
        val text = StringBuilder()
        var expiredDeadlines = 0
        val started = TimeSource.Monotonic.markNow()
        while (text.length < length && started.elapsedNow() < budget) {
            try {
                text.append(readString(deadline = readDeadline))
            } catch (_: SocketTimeoutException) {
                expiredDeadlines++
            } catch (_: SocketClosedException) {
                break
            }
        }
        return Received(text.toString(), expiredDeadlines, started.elapsedNow())
    }

    private companion object {
        val PACED_PARTS = listOf("PART1", "PART2", "PART3")
    }

    @Test
    fun nullBytesInData() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val dataReceived = Mutex(locked = true)

            // Data with embedded null bytes
            val dataWithNulls = byteArrayOf(0x48, 0x00, 0x65, 0x00, 0x6C, 0x00, 0x6C, 0x00, 0x6F, 0x00) // "H\0e\0l\0l\0o\0"
            var receivedData: ByteArray? = null

            val accepts = AcceptLog()
            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.serveEach(accepts) { client ->
                        val buffer = client.readBuffer(5.seconds)
                        receivedData = buffer.readByteArray(buffer.remaining())
                        dataReceived.unlock()
                    }
                }

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), hostname = "127.0.0.1")
            accepts.client(client)

            val sendBuffer = BufferFactory.Default.allocate(dataWithNulls.size)
            sendBuffer.writeBytes(dataWithNulls)
            sendBuffer.resetForRead()
            client.write(sendBuffer, 5.seconds)
            accepts.note("client wrote ${dataWithNulls.size} bytes")
            client.close()
            accepts.note("client closed")

            dataReceived.lockWithTimeout()
            assertContentEquals(dataWithNulls, receivedData, "Data with null bytes should be transferred intact\n${accepts.describe()}")

            server.close()
            serverJob.cancel()
        }
}
