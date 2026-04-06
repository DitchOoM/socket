package com.ditchoom.socket

import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.socket.transport.TcpByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that ConnectionContext (and its BufferPool) is cleaned up
 * when connections close, preventing direct buffer memory leaks.
 */
class ConnectionContextLifecycleTests {
    private val testOptions = ConnectionOptions(readTimeout = 5.seconds, writeTimeout = 5.seconds)

    @Test
    fun tcpByteStreamCloseClearsPool() =
        runTest {
            val context = ConnectionContext(testOptions)
            val mock = MockClientToServerSocket()
            mock.open(80, 5.seconds, "test")

            // Acquire a buffer to populate the pool
            val buf = context.pool.acquire(64)
            context.pool.release(buf)
            assertTrue(context.pool.stats().currentPoolSize > 0, "Pool should have buffers")

            val stream = TcpByteStream(mock, context)
            stream.close()

            assertEquals(0, context.pool.stats().currentPoolSize, "Pool should be cleared after close")
        }

    @Test
    fun codecConnectionCloseClearsOwnedPool() =
        runTest {
            val (clientStream, serverStream) = MemoryTransport.createPair()
            val context = ConnectionContext(testOptions)

            // Acquire a buffer to populate the pool
            val buf = context.pool.acquire(64)
            context.pool.release(buf)
            assertTrue(context.pool.stats().currentPoolSize > 0)

            val conn =
                CodecConnection(
                    stream = clientStream,
                    codec = com.ditchoom.socket.transport.TestStringCodec,
                    context = context,
                    ownsContext = true,
                )
            conn.close()

            assertEquals(0, context.pool.stats().currentPoolSize, "Owned pool should be cleared")
        }

    @Test
    fun codecConnectionCloseDoesNotClearSharedPool() =
        runTest {
            val (clientStream, _) = MemoryTransport.createPair()
            val context = ConnectionContext(testOptions)

            val buf = context.pool.acquire(64)
            context.pool.release(buf)
            val poolSizeBefore = context.pool.stats().currentPoolSize
            assertTrue(poolSizeBefore > 0)

            val conn =
                CodecConnection(
                    stream = clientStream,
                    codec = com.ditchoom.socket.transport.TestStringCodec,
                    context = context,
                    ownsContext = false,
                )
            conn.close()

            assertEquals(poolSizeBefore, context.pool.stats().currentPoolSize, "Shared pool should NOT be cleared")
        }

    @Test
    fun repeatedContextCreateAndCloseDoesNotLeak() =
        runTest {
            // Simulate many short-lived connections
            repeat(100) {
                val context = ConnectionContext(testOptions)
                val mock = MockClientToServerSocket()
                mock.open(80, 5.seconds, "test")

                val stream = TcpByteStream(mock, context)
                // Use the pool
                val buf = context.pool.acquire(1024)
                context.pool.release(buf)

                stream.close()
                assertEquals(0, context.pool.stats().currentPoolSize)
            }
        }
}
