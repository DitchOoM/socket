package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MemoryByteStreamTests {
    // ── read() → Data ──

    @Test
    fun readReturnsDataWhenDataAvailable() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            b.write("hello".toReadBuffer(), 5.seconds)

            val result = a.read(5.seconds)
            assertIs<ReadResult.Data>(result)
            assertEquals("hello", result.buffer.readString(5))
        }

    @Test
    fun readPreservesBufferContent() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
            val buf =
                com.ditchoom.buffer.BufferFactory.Default
                    .allocate(bytes.size)
            buf.writeBytes(bytes)
            buf.resetForRead()
            b.write(buf, 5.seconds)

            val result = a.read(5.seconds)
            assertIs<ReadResult.Data>(result)
            assertEquals(0x00, result.buffer.readByte())
            assertEquals(0x01, result.buffer.readByte())
            assertEquals(0x02, result.buffer.readByte())
            assertEquals(0xFF.toByte(), result.buffer.readByte())
        }

    // ── read() → End ──

    @Test
    fun readReturnsEndWhenPeerCloses() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            b.close()
            val result = a.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    @Test
    fun readReturnsEndAfterAllDataConsumed() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            b.write("data".toReadBuffer(), 5.seconds)
            b.close()

            // First read gets data
            val first = a.read(5.seconds)
            assertIs<ReadResult.Data>(first)

            // Second read gets End
            val second = a.read(5.seconds)
            assertIs<ReadResult.End>(second)
        }

    // ── write() ──

    @Test
    fun writeReturnsBytesWritten() =
        runTest {
            val (a, _) = MemoryTransport.createPair()
            val written = a.write("test".toReadBuffer(), 5.seconds)
            assertEquals(4, written.count)
        }

    @Test
    fun writeDoesNotAliasOriginalBuffer() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            val original =
                com.ditchoom.buffer.BufferFactory.Default
                    .allocate(4)
            original.writeInt(42)
            original.resetForRead()
            a.write(original, 5.seconds)

            // Modify original
            original.resetForWrite()
            original.writeInt(99)

            // Received data should still be 42
            val result = b.read(5.seconds)
            assertIs<ReadResult.Data>(result)
            assertEquals(42, result.buffer.readInt())
        }

    // ── writeGathered() ──

    @Test
    fun writeGatheredSendsMultipleBuffers() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            val buffers = listOf("ab".toReadBuffer(), "cd".toReadBuffer())
            val written = a.writeGathered(buffers, 5.seconds)
            assertEquals(4, written.count)

            // Each buffer sent separately
            val r1 = b.read(5.seconds)
            assertIs<ReadResult.Data>(r1)
            assertEquals("ab", r1.buffer.readString(2))

            val r2 = b.read(5.seconds)
            assertIs<ReadResult.Data>(r2)
            assertEquals("cd", r2.buffer.readString(2))
        }

    // ── isOpen ──

    @Test
    fun isOpenReflectsState() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            assertTrue(a.isOpen)
            b.close()
            a.read(5.seconds) // consume the End
            assertFalse(a.isOpen)
        }

    // ── bidirectional ──

    @Test
    fun bidirectionalCommunication() =
        runTest {
            val (a, b) = MemoryTransport.createPair()

            a.write("from-a".toReadBuffer(), 5.seconds)
            b.write("from-b".toReadBuffer(), 5.seconds)

            val receivedByB = b.read(5.seconds)
            assertIs<ReadResult.Data>(receivedByB)
            assertEquals("from-a", receivedByB.buffer.readString(6))

            val receivedByA = a.read(5.seconds)
            assertIs<ReadResult.Data>(receivedByA)
            assertEquals("from-b", receivedByA.buffer.readString(6))
        }

    // ── close() ──

    @Test
    fun closeSignalsEndToPeer() =
        runTest {
            val (a, b) = MemoryTransport.createPair()
            a.close()
            val result = b.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    // ── impossible state guards ──

    @Test
    fun readAfterOwnCloseReturnsEnd() =
        runTest {
            val (a, _) = MemoryTransport.createPair()
            a.close()
            val result = a.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    @Test
    fun writeAfterCloseThrowsSocketClosed() =
        runTest {
            val (a, _) = MemoryTransport.createPair()
            a.close()
            assertFailsWith<SocketClosedException> {
                a.write("fail".toReadBuffer(), 5.seconds)
            }
        }

    // ── MemoryTransport.connect() ──

    @Test
    fun transportConnectReturnsWorkingStream() =
        runTest {
            val transport = MemoryTransport()
            val stream = transport.connect("localhost", 8080)
            assertTrue(stream.isOpen)
            stream.close()
        }
}
