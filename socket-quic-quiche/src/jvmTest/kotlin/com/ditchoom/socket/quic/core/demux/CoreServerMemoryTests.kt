package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.CoreClient
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What one idle established connection costs its server in memory from the server's factory. On Android a direct
 * buffer lives on the Java heap, and a 1,500-byte one in the small non-movable space, so per-connection buffers decide
 * how many connections a process holds (a thousand of them once filled that space).
 */
class CoreServerMemoryTests {
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    /** Counts the bytes it hands out. The default factory's memory is released by the collector, so frees are not seen. */
    private class CountingFactory : BufferFactory {
        private val made = AtomicLong()

        fun madeBytes(): Long = made.get()

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder).also { made.addAndGet(size.toLong()) }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = BufferFactory.Default.wrap(array, byteOrder)
    }

    @Test
    fun anEstablishedServerConnectionTakesAtMostOneKibibyteFromTheServersFactory() =
        runBlocking {
            val factory = CountingFactory()
            withCoreEchoServer(factory = factory) { server ->
                val before = factory.madeBytes()
                val clients = ArrayList<CoreClient>()
                try {
                    repeat(CONNECTIONS) { clients += connectCore(server.port, loops = loops) }
                    while (server.counts.accepted < CONNECTIONS) delay(10.milliseconds)
                    delay(100.milliseconds)
                    val perConnection = (factory.madeBytes() - before) / CONNECTIONS
                    assertTrue(perConnection <= MAX_BYTES, "an established server connection took $perConnection B from the factory")
                } finally {
                    clients.forEach { it.connection.close(QuicError.NoError) }
                }
            }
        }

    private companion object {
        const val CONNECTIONS = 32
        const val MAX_BYTES = 1_024L
    }
}
