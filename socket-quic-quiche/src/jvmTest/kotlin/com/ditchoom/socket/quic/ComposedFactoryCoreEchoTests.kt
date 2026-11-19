package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.counting
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.edge.withEchoServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A factory the caller composed from the buffer library's parts (a pool, a limit that is the size asked for, a counter)
 * carries a core connection's echoes, and the counter and the pool show the buffers coming back for reuse.
 */
class ComposedFactoryCoreEchoTests {
    @Test
    fun aCallerComposedFactoryCarriesEchoesAndItsBuffersAreReused() =
        runBlocking {
            skipOnMissingNativeLib(ComposedFactoryCoreEchoTests::class) {
                withEchoServer { port ->
                    val pool =
                        BufferPool(ThreadingMode.MultiThreaded, maxPoolSize = 64, defaultBufferSize = 2048, factory = BufferFactory.Default)
                    val counted = ExactLimit(pool).counting()
                    val client =
                        coreConnect(
                            mismatch = MismatchResponse.Fail,
                            host = "127.0.0.1",
                            port = port,
                            serverName = "localhost",
                            options = coreTestOptions,
                            factory = counted,
                            api = loadQuicheApi(),
                            timeout = 10.seconds,
                            readPolicy = ReadPolicy.Bounded(10.seconds),
                            writePolicy = WritePolicy.Bounded(10.seconds),
                        )
                    val stream = client.connection.openStream()
                    val payload = ByteArray(64) { it.toByte() }
                    val rounds = 200
                    repeat(rounds) { assertContentEquals(payload, echoOver(stream, payload)) }
                    client.connection.close(QuicError.NoError)
                    assertTrue(counted.allocationCount >= rounds, "one result buffer per echo at least: ${counted.allocationCount}")
                    val stats = pool.stats()
                    assertTrue(stats.poolHits >= rounds / 2, "the result buffers are reused: $stats")
                    assertEquals(stats.totalAllocations, stats.poolHits + stats.poolMisses)
                }
            }
        }
}

/** [pool]'s buffers with their `limit` at the size asked for: the pool's own start at their whole class. */
internal class ExactLimit(
    private val pool: BufferFactory,
) : BufferFactory {
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer = pool.allocate(size, byteOrder).also { it.setLimit(size) }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = pool.wrap(array, byteOrder)
}
