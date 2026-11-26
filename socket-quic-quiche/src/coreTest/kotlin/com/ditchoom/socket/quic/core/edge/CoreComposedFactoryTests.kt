package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.CountingBufferFactory
import com.ditchoom.buffer.counting
import com.ditchoom.buffer.withSizeLimit
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * On every platform, over a real loopback socket: a factory composed from the buffer library's parts (a counter, a size
 * limit, over the pooled network factory) carries a core connection's echoes and closes it, the connection allocating from
 * the factory it was given; and concurrent streams of one connection each echo their own bytes.
 */
class CoreComposedFactoryTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aCountedNetworkFactoryCarriesEchoesAndCloses() =
        coreTest("CoreComposedFactoryTests.aCountedNetworkFactoryCarriesEchoesAndCloses") {
            val counted = BufferFactory.network().counting()
            echoRounds(counted)
            assertAllocatedFrom(counted)
        }

    @Test
    fun aSizeLimitedCountedNetworkFactoryCarriesEchoesAndCloses() =
        coreTest("CoreComposedFactoryTests.aSizeLimitedCountedNetworkFactoryCarriesEchoesAndCloses") {
            val counted = BufferFactory.network().withSizeLimit(SIZE_LIMIT).counting()
            echoRounds(counted)
            assertAllocatedFrom(counted)
        }

    @Test
    fun eightConcurrentStreamsEachEchoTheirOwnBytes() =
        coreTest("CoreComposedFactoryTests.eightConcurrentStreamsEachEchoTheirOwnBytes") {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                val payloads = List(8) { Random(it).nextBytes(64 * 1024) }
                val echoed = payloads.map { payload -> async { echoOver(client.connection.openStream(), payload) } }.awaitAll()
                payloads.zip(echoed).forEachIndexed { i, (sent, back) -> assertContentEquals(sent, back, "stream $i") }
                client.connection.close(QuicError.NoError)
            }
        }

    private fun assertAllocatedFrom(counted: CountingBufferFactory) =
        assertTrue(counted.allocationCount >= ROUNDS, "the connection allocates from its factory: ${counted.allocationCount}")

    private suspend fun echoRounds(factory: CountingBufferFactory) {
        withCoreEchoServer { server ->
            val client = connectCore(server.port, loops = loops, factory = factory)
            val stream = client.connection.openStream()
            repeat(ROUNDS) { round ->
                val payload = ByteArray(ECHO_BYTES) { (it + round).toByte() }
                assertContentEquals(payload, echoOver(stream, payload), "round $round")
            }
            client.connection.close(QuicError.NoError)
        }
    }

    private companion object {
        const val ROUNDS = 50
        const val ECHO_BYTES = 64
        const val SIZE_LIMIT = 1 shl 20
    }
}
