package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        val buffer = client.read(5.seconds)
                        buffer.resetForRead()
                        receivedData = buffer.readByteArray(buffer.remaining())
                        dataReceived.unlock()
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

            val sendBuffer = PlatformBuffer.allocate(binaryData.size)
            sendBuffer.writeBytes(binaryData)
            sendBuffer.resetForRead()
            client.write(sendBuffer, 5.seconds)
            client.close()

            dataReceived.lockWithTimeout()
            assertContentEquals(binaryData, receivedData, "Binary data should be transferred intact")

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

        val serverJob =
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                serverFlow.collect { client ->
                    val receivedBytes = mutableListOf<Byte>()

                    // Read until we have all the data
                    while (receivedBytes.size < size) {
                        try {
                            val buffer = client.read(10.seconds)
                            buffer.resetForRead()
                            val chunk = buffer.readByteArray(buffer.remaining())
                            receivedBytes.addAll(chunk.toList())
                        } catch (e: SocketException) {
                            // Connection closed or other error
                            break
                        }
                    }

                    receivedSize = receivedBytes.size
                    if (receivedSize == size) {
                        val receivedArray = receivedBytes.toByteArray()
                        checksumMatch = testData.contentEquals(receivedArray)
                    }

                    transferComplete.unlock()
                    client.close()
                }
            }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 10.seconds, hostname = "127.0.0.1")

        // Send data in chunks
        var offset = 0
        val chunkSize = 8192
        while (offset < size) {
            val remaining = minOf(chunkSize, size - offset)
            val chunk = PlatformBuffer.allocate(remaining)
            chunk.writeBytes(testData, offset, remaining)
            chunk.resetForRead()

            var written = 0
            while (written < remaining) {
                val w = client.write(chunk, 10.seconds)
                if (w <= 0) break
                written += w
            }
            offset += remaining
        }
        client.close()

        transferComplete.lockWithTimeout()
        assertEquals(size, receivedSize, "Should receive all $size bytes")
        assertTrue(checksumMatch, "Data checksum should match for $size byte transfer")

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
                        // Read from client
                        val received = client.readString(timeout = 5.seconds)
                        serverReceivedCorrect = (received == clientToServerData)

                        // Send to client
                        client.writeString(serverToClientData)
                        client.close()
                        testComplete.unlock()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

            // Send to server
            client.writeString(clientToServerData)

            // Read from server
            val received = client.readString(timeout = 5.seconds)
            clientReceivedCorrect = (received == serverToClientData)

            client.close()
            testComplete.lockWithTimeout()

            assertTrue(serverReceivedCorrect, "Server should receive correct data from client")
            assertTrue(clientReceivedCorrect, "Client should receive correct data from server")

            server.close()
            serverJob.cancel()
        }

    @Ignore // ClosedChannelException race in AsyncServerSocket accept loop - fix tracked separately
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

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        val sb = StringBuilder()
                        // Read until we have all the data or connection closes
                        // TCP may coalesce multiple writes into single reads
                        while (sb.length < expectedString.length) {
                            try {
                                val chunk = client.readString(timeout = 1.seconds)
                                sb.append(chunk)
                            } catch (e: SocketClosedException) {
                                // Connection closed, we have all the data
                                break
                            }
                        }
                        receivedData = sb.toString()
                        dataReceived.unlock()
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

            // Send many small messages
            repeat(messageCount) { i ->
                val msg = "msg$i;"
                client.writeString(msg)
            }
            client.close()

            dataReceived.lockWithTimeout()
            assertEquals(expectedString, receivedData, "All small messages should be received in order")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun partialReadHandling() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        clientConnected.unlock()
                        // Send data in parts with delays
                        client.writeString("PART1")
                        kotlinx.coroutines.delay(50)
                        client.writeString("PART2")
                        kotlinx.coroutines.delay(50)
                        client.writeString("PART3")
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
            clientConnected.lockWithTimeout()

            // Read all data - may come in chunks
            val allData = StringBuilder()
            while (true) {
                try {
                    val chunk = client.readString(timeout = 1.seconds)
                    allData.append(chunk)
                    if (allData.toString() == "PART1PART2PART3") break
                } catch (e: SocketClosedException) {
                    break
                }
            }

            assertEquals("PART1PART2PART3", allData.toString(), "All parts should be received")

            client.close()
            server.close()
            serverJob.cancel()
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

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        val buffer = client.read(5.seconds)
                        buffer.resetForRead()
                        receivedData = buffer.readByteArray(buffer.remaining())
                        dataReceived.unlock()
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

            val sendBuffer = PlatformBuffer.allocate(dataWithNulls.size)
            sendBuffer.writeBytes(dataWithNulls)
            sendBuffer.resetForRead()
            client.write(sendBuffer, 5.seconds)
            client.close()

            dataReceived.lockWithTimeout()
            assertContentEquals(dataWithNulls, receivedData, "Data with null bytes should be transferred intact")

            server.close()
            serverJob.cancel()
        }
}
