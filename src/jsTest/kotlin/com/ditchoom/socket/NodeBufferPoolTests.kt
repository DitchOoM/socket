package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that small Node.js Buffers from the shared pool are correctly isolated.
 *
 * Node.js pools small Buffers (< 8KB) into a shared ArrayBuffer. Without copying,
 * a zero-copy Int8Array view can be silently overwritten when Node.js reuses the
 * same ArrayBuffer region for the next data event. These tests verify that each
 * read returns its own independent data.
 */
class NodeBufferPoolTests {
    /**
     * Sends many small, distinct messages through a real TCP connection.
     * Each message is < 4KB (well within Node.js pool threshold).
     * Verifies that every message read back matches what was sent — no corruption
     * from ArrayBuffer reuse.
     */
    @Test
    fun smallBuffersNotCorruptedBySubsequentReads() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val messageCount = 50
            val messageSize = 1024 // 1KB — pooled by Node.js

            val serverReady = Mutex(locked = true)
            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        serverReady.unlock()
                        // Send distinct messages: each byte in message N is (N % 256)
                        repeat(messageCount) { i ->
                            val buf = BufferFactory.Default.allocate(messageSize)
                            repeat(messageSize) { buf.writeByte((i % 256).toByte()) }
                            buf.resetForRead()
                            client.write(buf, 5.seconds)
                        }
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            // Read all data and collect buffers before inspecting them.
            // This is critical: if pool corruption exists, earlier buffers would
            // be overwritten by the time we read later ones.
            val allBytes = mutableListOf<ByteArray>()
            var totalRead = 0
            val expectedTotal = messageCount * messageSize

            while (totalRead < expectedTotal) {
                val buffer =
                    try {
                        client.read(5.seconds)
                    } catch (_: SocketClosedException) {
                        break
                    }
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                allBytes.add(buffer.readByteArray(remaining))
                totalRead += remaining
            }

            assertEquals(expectedTotal, totalRead, "Should have read all $expectedTotal bytes")

            // Reconstruct the full byte stream and verify each message's pattern
            val fullStream = ByteArray(totalRead)
            var offset = 0
            for (chunk in allBytes) {
                chunk.copyInto(fullStream, offset)
                offset += chunk.size
            }

            repeat(messageCount) { i ->
                val expected = (i % 256).toByte()
                val msgStart = i * messageSize
                repeat(messageSize) { j ->
                    assertEquals(
                        expected,
                        fullStream[msgStart + j],
                        "Byte at message $i offset $j corrupted: expected $expected, got ${fullStream[msgStart + j]}",
                    )
                }
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    /**
     * Verifies that a rapid burst of tiny messages (64 bytes each) preserves
     * data integrity. Tiny messages are most likely to share a single pooled
     * ArrayBuffer.
     */
    @Test
    fun tinyBurstMessagesPreserveIntegrity() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val messageCount = 200
            val messageSize = 64 // Very small — high pool reuse probability

            val serverReady = Mutex(locked = true)
            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        serverReady.unlock()
                        // Each message: 4-byte sequence number + fill byte pattern
                        repeat(messageCount) { i ->
                            val buf = BufferFactory.Default.allocate(messageSize)
                            // Write sequence number as first 4 bytes
                            buf.writeByte(((i shr 24) and 0xFF).toByte())
                            buf.writeByte(((i shr 16) and 0xFF).toByte())
                            buf.writeByte(((i shr 8) and 0xFF).toByte())
                            buf.writeByte((i and 0xFF).toByte())
                            // Fill rest with pattern
                            repeat(messageSize - 4) { buf.writeByte((i % 251).toByte()) }
                            buf.resetForRead()
                            client.write(buf, 5.seconds)
                        }
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            // Read all data
            val allBytes = mutableListOf<ByteArray>()
            var totalRead = 0
            val expectedTotal = messageCount * messageSize

            while (totalRead < expectedTotal) {
                val buffer =
                    try {
                        client.read(5.seconds)
                    } catch (_: SocketClosedException) {
                        break
                    }
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                allBytes.add(buffer.readByteArray(remaining))
                totalRead += remaining
            }

            assertEquals(expectedTotal, totalRead, "Should have read all $expectedTotal bytes")

            // Reconstruct and verify sequence numbers
            val fullStream = ByteArray(totalRead)
            var offset = 0
            for (chunk in allBytes) {
                chunk.copyInto(fullStream, offset)
                offset += chunk.size
            }

            var corruptCount = 0
            repeat(messageCount) { i ->
                val msgStart = i * messageSize
                val seqNum =
                    ((fullStream[msgStart].toInt() and 0xFF) shl 24) or
                        ((fullStream[msgStart + 1].toInt() and 0xFF) shl 16) or
                        ((fullStream[msgStart + 2].toInt() and 0xFF) shl 8) or
                        (fullStream[msgStart + 3].toInt() and 0xFF)
                if (seqNum != i) corruptCount++

                val expectedFill = (i % 251).toByte()
                for (j in 4 until messageSize) {
                    if (fullStream[msgStart + j] != expectedFill) {
                        corruptCount++
                        break
                    }
                }
            }
            assertTrue(corruptCount == 0, "$corruptCount of $messageCount messages were corrupted by buffer pool reuse")

            client.close()
            server.close()
            serverJob.cancel()
        }
}
