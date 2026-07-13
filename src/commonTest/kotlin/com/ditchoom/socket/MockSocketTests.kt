package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.data.readBuffer
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
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            mock.open(80, "localhost")

            val expected = BufferFactory.Default.allocate(3)
            expected.writeByte(1)
            expected.writeByte(2)
            expected.writeByte(3)
            expected.resetForRead()
            mock.enqueueRead(expected)

            val result = mock.readBuffer(1.seconds)
            assertEquals(3, result.remaining())
            assertEquals(1.toByte(), result.readByte())
            assertEquals(2.toByte(), result.readByte())
            assertEquals(3.toByte(), result.readByte())
        }

    @Test
    fun mockWriteRecordsData() =
        runTest {
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            mock.open(80, "localhost")

            val buffer = BufferFactory.Default.allocate(2)
            buffer.writeByte(42)
            buffer.writeByte(43)
            buffer.resetForRead()

            val written = mock.write(buffer, 1.seconds).count
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
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            mock.open(80, "localhost")
            mock.close()

            assertFalse(mock.isOpen)
            assertFailsWith<SocketClosedException> {
                mock.readBuffer(1.seconds)
            }
        }

    @Test
    fun mockReadErrorPropagates() =
        runTest {
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            mock.open(80, "localhost")

            mock.enqueueReadError(SocketIOException("test error"))

            assertFailsWith<SocketException> {
                mock.readBuffer(1.seconds)
            }
        }

    @Test
    fun mockDisconnectInterruptsRead() =
        runTest {
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            mock.open(80, "localhost")

            mock.simulateDisconnect()
            assertFalse(mock.isOpen)

            assertFailsWith<SocketClosedException> {
                mock.readBuffer(1.seconds)
            }
        }

    @Test
    fun mockReadBytesConvenience() =
        runTest {
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            mock.open(80, "localhost")

            mock.enqueueReadBytes(10, 20, 30)

            val result = mock.readBuffer(1.seconds)
            assertEquals(3, result.remaining())
            assertEquals(10.toByte(), result.readByte())
            assertEquals(20.toByte(), result.readByte())
            assertEquals(30.toByte(), result.readByte())
        }

    @Test
    fun mockVerifyOpened() =
        runTest {
            val mock = MockClientToServerSocket(TransportConfig(connectTimeout = 1.seconds))
            assertFalse(mock.openCalled)

            mock.open(80, "localhost")
            mock.verifyOpened()
            assertTrue(mock.isOpen)
        }
}
