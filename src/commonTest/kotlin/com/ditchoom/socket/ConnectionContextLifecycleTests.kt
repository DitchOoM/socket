package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.BufferPool
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
    private val testOptions =
        ConnectionOptions(
            readTimeout = 5.seconds,
            writeTimeout = 5.seconds,
            bufferFactory = BufferFactory.Default,
        )

    @Test
    fun tcpByteStreamCloseClearsPool() =
        runTest {
            val context = ConnectionContext(testOptions)
            val mock = MockClientToServerSocket()
            mock.open(80, 5.seconds, "test")

            val buf = context.pool.acquire(64)
            context.pool.release(buf)
            assertTrue(context.pool.stats().currentPoolSize > 0, "Pool should have buffers")

            val stream = TcpByteStream(mock, context)
            stream.close()

            assertEquals(0, context.pool.stats().currentPoolSize, "Pool should be cleared after close")
        }

    @Test
    fun codecConnectionCloseDoesNotClearPoolDirectly() =
        runTest {
            val (clientStream, _) = MemoryTransport.createPair()
            val pool = BufferPool()

            val buf = pool.acquire(64)
            pool.release(buf)
            val poolSizeBefore = pool.stats().currentPoolSize
            assertTrue(poolSizeBefore > 0)

            val conn =
                CodecConnection(
                    stream = clientStream,
                    codec = com.ditchoom.socket.transport.TestStringCodec,
                    pool = pool,
                    options = testOptions,
                )
            conn.close()

            // CodecConnection borrows the pool — it should NOT clear it
            assertEquals(poolSizeBefore, pool.stats().currentPoolSize)
        }

    @Test
    fun tcpByteStreamOwnsContextLifecycle() =
        runTest {
            val context = ConnectionContext(testOptions)
            val mock = MockClientToServerSocket()
            mock.open(80, 5.seconds, "test")

            // CodecConnection borrows pool, TcpByteStream owns context
            val tcpStream = TcpByteStream(mock, context)
            val conn =
                CodecConnection(
                    stream = tcpStream,
                    codec = com.ditchoom.socket.transport.TestStringCodec,
                    pool = context.pool,
                    options = testOptions,
                )

            val buf = context.pool.acquire(64)
            context.pool.release(buf)
            assertTrue(context.pool.stats().currentPoolSize > 0)

            // CodecConnection.close() → TcpByteStream.close() → context.close()
            conn.close()

            assertEquals(0, context.pool.stats().currentPoolSize, "Pool cleared via TcpByteStream ownership chain")
        }

    @Test
    fun repeatedContextCreateAndCloseDoesNotLeak() =
        runTest {
            repeat(100) {
                val context = ConnectionContext(testOptions)
                val mock = MockClientToServerSocket()
                mock.open(80, 5.seconds, "test")

                val stream = TcpByteStream(mock, context)
                val buf = context.pool.acquire(1024)
                context.pool.release(buf)

                stream.close()
                assertEquals(0, context.pool.stats().currentPoolSize)
            }
        }
}
