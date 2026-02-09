package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MockSocketTests {
    @Test
    fun mockReadReturnsEnqueuedData() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 1.seconds, "localhost")

            val expected = PlatformBuffer.allocate(3)
            expected.writeByte(1)
            expected.writeByte(2)
            expected.writeByte(3)
            // Leave position at end (like a real socket read) — caller calls resetForRead()
            mock.enqueueRead(expected)

            val result = mock.read(1.seconds)
            result.resetForRead()
            assertEquals(3, result.remaining())
            assertEquals(1.toByte(), result.readByte())
            assertEquals(2.toByte(), result.readByte())
            assertEquals(3.toByte(), result.readByte())
        }

    @Test
    fun mockWriteRecordsData() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 1.seconds, "localhost")

            val buffer = PlatformBuffer.allocate(2)
            buffer.writeByte(42)
            buffer.writeByte(43)
            buffer.resetForRead()

            val written = mock.write(buffer, 1.seconds)
            assertEquals(2, written)
            mock.verifyWriteCount(1)

            val recorded = mock.writtenBuffers[0]
            recorded.position(0)
            assertEquals(42.toByte(), recorded.readByte())
            assertEquals(43.toByte(), recorded.readByte())
        }

    @Test
    fun mockReadAfterCloseThrows() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 1.seconds, "localhost")
            mock.close()

            assertFalse(mock.isOpen())
            assertFailsWith<SocketClosedException> {
                mock.read(1.seconds)
            }
        }

    @Test
    fun mockReadErrorPropagates() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 1.seconds, "localhost")

            mock.enqueueReadError(SocketException("test error"))

            assertFailsWith<SocketException> {
                mock.read(1.seconds)
            }
        }

    @Test
    fun mockDisconnectInterruptsRead() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 1.seconds, "localhost")

            mock.simulateDisconnect()
            assertFalse(mock.isOpen())

            assertFailsWith<SocketClosedException> {
                mock.read(1.seconds)
            }
        }

    @Test
    fun mockReadBytesConvenience() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 1.seconds, "localhost")

            mock.enqueueReadBytes(10, 20, 30)

            val result = mock.read(1.seconds)
            result.resetForRead()
            assertEquals(3, result.remaining())
            assertEquals(10.toByte(), result.readByte())
            assertEquals(20.toByte(), result.readByte())
            assertEquals(30.toByte(), result.readByte())
        }

    @Test
    fun mockVerifyOpened() =
        runTest {
            val mock = MockClientToServerSocket()
            assertFalse(mock.openCalled)

            mock.open(80, 1.seconds, "localhost")
            mock.verifyOpened()
            assertTrue(mock.isOpen())
        }
}
