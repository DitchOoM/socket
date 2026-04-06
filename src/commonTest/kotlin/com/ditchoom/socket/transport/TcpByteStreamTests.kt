package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.MockClientToServerSocket
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TcpByteStreamTests {
    private val testOptions = com.ditchoom.socket.ConnectionOptions(readTimeout = 5.seconds)

    private fun createStream(): Pair<TcpByteStream, MockClientToServerSocket> {
        val mock = MockClientToServerSocket()
        val context = com.ditchoom.socket.ConnectionContext(testOptions)
        val stream = TcpByteStream(mock, context)
        return stream to mock
    }

    // ── read() → ReadResult.Data ──

    @Test
    fun readReturnsDataOnSuccess() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadBytes(0x48, 0x69) // "Hi"

            val result = stream.read(5.seconds)
            assertIs<ReadResult.Data>(result)
            assertEquals(2, result.buffer.remaining())
        }

    // ── read() → ReadResult.End ──

    @Test
    fun readReturnsEndOnEndOfStream() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketClosedException.EndOfStream())

            val result = stream.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    @Test
    fun readReturnsEndOnGeneralClose() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.simulateDisconnect()

            // General close maps to ReadResult.End — byte stream abstracts away close reason
            val result = stream.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    // ── read() → ReadResult.Reset ──

    @Test
    fun readReturnsResetOnConnectionReset() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketClosedException.ConnectionReset("peer reset"))

            val result = stream.read(5.seconds)
            assertIs<ReadResult.Reset>(result)
        }

    // ── read() throws on truly exceptional errors ──

    @Test
    fun readThrowsOnTimeout() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketTimeoutException("timed out"))

            assertFailsWith<SocketTimeoutException> {
                stream.read(5.seconds)
            }
        }

    @Test
    fun readThrowsOnIOError() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketIOException("disk full"))

            assertFailsWith<SocketIOException> {
                stream.read(5.seconds)
            }
        }

    @Test
    fun readThrowsOnSSLError() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SSLHandshakeFailedException("bad cert"))

            assertFailsWith<SSLHandshakeFailedException> {
                stream.read(5.seconds)
            }
        }

    @Test
    fun readThrowsOnBrokenPipe() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketClosedException.BrokenPipe("broken pipe"))

            // BrokenPipe maps to ReadResult.End — byte stream abstracts away close reason
            val result = stream.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    // ── write() ──

    @Test
    fun writeReturnsBytesWritten() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            val data = "hello".toReadBuffer()

            val written = stream.write(data, 5.seconds)
            assertEquals(5, written.count)
            mock.verifyWriteCount(1)
        }

    @Test
    fun writeThrowsOnClosedSocket() =
        runTest {
            val (stream, mock) = createStream()
            // Don't open — socket is closed
            val data = "hello".toReadBuffer()

            assertFailsWith<SocketClosedException> {
                stream.write(data, 5.seconds)
            }
        }

    // ── writeGathered() ──

    @Test
    fun writeGatheredCombinesBuffers() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            val buffers = listOf("ab".toReadBuffer(), "cd".toReadBuffer())

            val written = stream.writeGathered(buffers, 5.seconds)
            assertEquals(4, written.count)
            mock.verifyWriteCount(2)
        }

    // ── isOpen ──

    @Test
    fun isOpenReflectsSocketState() =
        runTest {
            val (stream, mock) = createStream()
            assertFalse(stream.isOpen)

            mock.open(80, 5.seconds, "test")
            assertTrue(stream.isOpen)

            stream.close()
            assertFalse(stream.isOpen)
        }

    // ── close() ──

    @Test
    fun closeDelegatesToSocket() =
        runTest {
            val (stream, mock) = createStream()
            mock.open(80, 5.seconds, "test")
            assertTrue(mock.isOpen())

            stream.close()
            assertFalse(mock.isOpen())
        }
}
