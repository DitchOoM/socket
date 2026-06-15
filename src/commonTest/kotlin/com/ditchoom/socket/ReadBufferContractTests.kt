package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.data.readBuffer
import com.ditchoom.data.readFlowString
import com.ditchoom.data.readInto
import com.ditchoom.data.readString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the buffer contract: read() returns buffers in read-ready mode
 * (position = 0, limit = bytesRead). No caller should need to call resetForRead().
 *
 * These tests exist to prevent regressions on the double-flip bug where
 * resetForRead() was called on already-flipped buffers, zeroing all data.
 */
class ReadBufferContractTests {
    private val testData = "Hello, World!"

    private fun mockWithData(): MockClientToServerSocket {
        val mock = MockClientToServerSocket()
        val buf = BufferFactory.Default.allocate(64)
        buf.writeString(testData)
        buf.resetForRead()
        mock.enqueueRead(buf)
        return mock
    }

    // ── read(timeout) contract ──

    @Test
    fun readReturnsBufferInReadReadyMode() =
        runTest {
            val mock = mockWithData()
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val buffer = mock.readBuffer(5.seconds)
            assertEquals(0, buffer.position(), "Buffer position should be 0 (read-ready)")
            assertTrue(buffer.remaining() > 0, "Buffer should have readable data")
        }

    @Test
    fun readBufferContainsCorrectData() =
        runTest {
            val mock = mockWithData()
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val buffer = mock.readBuffer(5.seconds)
            val str = buffer.readString(buffer.remaining())
            assertEquals(testData, str)
        }

    // ── readString() must not double-flip ──

    @Test
    fun readStringReturnsActualData() =
        runTest {
            val mock = mockWithData()
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val str = mock.readString()
            assertEquals(testData, str, "readString() must return data without double-flip")
        }

    @Test
    fun readStringNeverReturnsEmptyForNonEmptyBuffer() =
        runTest {
            val mock = mockWithData()
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val str = mock.readString()
            assertTrue(str.isNotEmpty(), "readString() must never return empty when data was sent")
        }

    // ── readFlowString() must not double-flip ──

    @Test
    fun readFlowStringYieldsActualData() =
        runTest {
            val mock = mockWithData()
            mock.enqueueReadError(SocketClosedException.EndOfStream())
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val str = mock.readFlowString().first()
            assertEquals(testData, str, "readFlowString() must yield data without double-flip")
        }

    // ── read(buffer, timeout) must not double-flip ──

    @Test
    fun readIntoBufferCopiesCorrectData() =
        runTest {
            val mock = mockWithData()
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val dest = BufferFactory.Default.allocate(64)
            val bytesRead = mock.readInto(dest, 5.seconds)

            assertTrue(bytesRead > 0, "Should read bytes")
            assertEquals(testData.length, bytesRead)
            dest.resetForRead()
            val str = dest.readString(dest.remaining())
            assertEquals(testData, str, "read(buffer) must copy correct data")
        }

    // ── ByteStream read() returns Data correctly ──

    @Test
    fun byteStreamReadReturnsReadableData() =
        runTest {
            val mock = mockWithData()
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val result = mock.read(5.seconds)
            assertTrue(result is ReadResult.Data, "Should be Data result")

            val buffer = result.buffer
            assertEquals(0, buffer.position(), "ByteStream buffer should be read-ready")
            val str = buffer.readString(buffer.remaining())
            assertEquals(testData, str)
        }

    // ── ByteStream handles large reads without overflow ──

    @Test
    fun byteStreamLargeReadDoesNotOverflow() =
        runTest {
            val mock = MockClientToServerSocket()
            // 64KB payload — well above the 8KB defaultBufferSize
            val size = 65536
            val largeBuf = BufferFactory.Default.allocate(size)
            repeat(size) { largeBuf.writeByte((it % 256).toByte()) }
            largeBuf.resetForRead()
            mock.enqueueRead(largeBuf)
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            val result = mock.read(5.seconds)
            assertTrue(result is ReadResult.Data, "Should be Data result, not overflow")

            val buffer = result.buffer
            assertEquals(0, buffer.position(), "Buffer should be read-ready")
            assertEquals(size, buffer.remaining(), "All $size bytes should be available")

            // Verify data integrity
            repeat(size) { i ->
                assertEquals(
                    (i % 256).toByte(),
                    buffer.readByte(),
                    "Byte mismatch at index $i",
                )
            }
        }

    // ── Multi-read: ensure each read returns correct independent data ──

    @Test
    fun consecutiveReadsEachReturnCorrectData() =
        runTest {
            val mock = MockClientToServerSocket()
            listOf("first", "second", "third").forEach { msg ->
                val buf = BufferFactory.Default.allocate(32)
                buf.writeString(msg)
                buf.resetForRead()
                mock.enqueueRead(buf)
            }
            mock.open(80, "test", TransportConfig(connectTimeout = 5.seconds))

            assertEquals("first", mock.readString())
            assertEquals("second", mock.readString())
            assertEquals("third", mock.readString())
        }
}
