package com.ditchoom.socket

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
 * Verifies the caller-owned buffer policy:
 *
 * - Closing a connection never clears a caller-supplied [BufferPool].
 * - Pool lifecycle is the caller's concern (they call `pool.clear()` or drop the reference).
 * - Non-pool factories flow through unchanged — allocations become fresh/free pairs.
 *
 * Previously the socket library owned a per-connection pool and cleared it on close.
 * With that removed, the lifecycle tests now assert the opposite invariant: the
 * library must NOT touch the caller's pool.
 */
class CallerOwnedPoolLifecycleTests {
    private val testOptions =
        ConnectionOptions(
            readTimeout = 5.seconds,
            writeTimeout = 5.seconds,
        )

    @Test
    fun tcpByteStreamCloseDoesNotTouchCallerPool() =
        runTest {
            val pool = BufferPool()
            val mock = MockClientToServerSocket()
            mock.open(80, 5.seconds, "test")

            // Warm the pool so currentPoolSize > 0 is observable.
            val buf = pool.acquire(64)
            pool.release(buf)
            val poolSizeBefore = pool.stats().currentPoolSize
            assertTrue(poolSizeBefore > 0)

            val stream = TcpByteStream(mock)
            stream.close()

            assertEquals(poolSizeBefore, pool.stats().currentPoolSize, "Library must not clear a caller-owned pool")
        }

    @Test
    fun codecConnectionCloseDoesNotTouchCallerPool() =
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
                    bufferPool = pool,
                    options = testOptions.copy(bufferPool = pool),
                )
            conn.close()

            assertEquals(poolSizeBefore, pool.stats().currentPoolSize, "CodecConnection must not clear caller-owned pool")
        }

    @Test
    fun repeatedConnectAndCloseDoesNotLeakCallerPoolContents() =
        runTest {
            val pool = BufferPool()
            repeat(100) {
                val mock = MockClientToServerSocket()
                mock.open(80, 5.seconds, "test")
                val stream = TcpByteStream(mock)
                val buf = pool.acquire(1024)
                pool.release(buf)
                stream.close()
            }
            // The pool is still the caller's — they clear it when they're done.
            assertTrue(pool.stats().currentPoolSize > 0, "Pool retains its buffers across connection churn")
            pool.clear()
            assertEquals(0, pool.stats().currentPoolSize, "Explicit caller-owned clear works")
        }
}
