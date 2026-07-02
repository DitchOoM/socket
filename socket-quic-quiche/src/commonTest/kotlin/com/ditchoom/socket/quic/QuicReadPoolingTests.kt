package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.counting
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for QUIC read-path buffer recycling — the quiche-layer companion of the TCP
 * `ReadBufferPoolingTest`. [DriverStreamAdapter.streamRead] used to allocate a fresh 64 KB buffer
 * from the leaf factory on EVERY stream read (and [DriverDatagramAdapter.receiveDatagram] a fresh
 * 1350-byte buffer per datagram); under the default GC-reclaimed factory those accumulate to the
 * JVM direct-memory cap under high read throughput. Reads now draw from the driver's per-connection
 * [QuicheDriver.streamReadPool] / [QuicheDriver.recvBufPool], and the consumer's `freeNativeMemory()`
 * recycles each buffer back to its pool.
 *
 * Deterministic: a counting leaf factory records how many buffers the driver actually allocates.
 * With pooling that is a handful (driver scratch + pool misses); pre-fix it was ≈ one per read.
 */
class QuicReadPoolingTests {
    private fun driverWith(
        api: StubQuicheApi,
        factory: BufferFactory,
    ): QuicheDriver =
        QuicheDriver(
            api = api,
            conn = QuicheConn(1L),
            bufferFactory = factory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
        )

    private suspend fun openStream(driver: QuicheDriver): StreamSlot {
        val deferred = CompletableDeferred<StreamSlot>()
        driver.commands.send(QuicheCmd.OpenStream(deferred))
        return withTimeout(2.seconds) { deferred.await() }
    }

    // Both native-memory leaf factories QUIC accepts (quicBufferFactory() rejects heap factories
    // at setup): deterministic() is the network() default; Default is the GC-reclaimed override.
    private val leafFactories =
        listOf(
            "deterministic" to { BufferFactory.deterministic() },
            "default" to { BufferFactory.Default },
        )

    @Test
    fun streamReadBuffersAreRecycledNotAllocatedPerRead() =
        runQuicTest {
            for ((name, leaf) in leafFactories) {
                val counting = leaf().counting()
                val api = StubQuicheApi().apply { streamRecvResult = StreamRecvResult.Data(1024, false) }
                val driver = driverWith(api, counting)
                driver.start(this)
                try {
                    val slot = openStream(driver)
                    val stream = QuicheStreamByteStream(slot.id, DriverStreamAdapter(driver, slot), driver.streamReadPool)
                    repeat(300) {
                        val result = withTimeout(2.seconds) { stream.read(2.seconds) }
                        assertIs<ReadResult.Data>(result, "unexpected non-data read: $result")
                        // The consumer hands ownership back after consuming — recycles to the pool.
                        result.buffer.freeIfNeeded()
                    }
                } finally {
                    driver.destroy()
                }
                val allocations = counting.allocationCount
                // Pooling ⇒ the leaf factory is hit only for driver scratch buffers + pool misses,
                // NOT once per read (pre-fix: 300+ allocations here).
                assertTrue(
                    allocations < 20,
                    "[$name] stream read buffers should be pooled/recycled: expected < 20 leaf allocations, got $allocations over 300 reads",
                )
            }
        }

    @Test
    fun datagramReceiveBuffersAreRecycledNotAllocatedPerReceive() =
        runQuicTest {
            for ((name, leaf) in leafFactories) {
                val counting = leaf().counting()
                val api = StubQuicheApi().apply { dgramRecvResult = StreamRecvResult.Data(3, false) }
                val driver = driverWith(api, counting)
                val adapter = DriverDatagramAdapter(driver)
                driver.start(this)
                try {
                    repeat(300) {
                        val result = withTimeout(2.seconds) { adapter.receiveDatagram() }
                        assertIs<DatagramReceiveResult.Received>(result)
                        result.buffer.freeIfNeeded()
                    }
                } finally {
                    driver.destroy()
                }
                val allocations = counting.allocationCount
                assertTrue(
                    allocations < 20,
                    "[$name] datagram receive buffers should be pooled/recycled: expected < 20 leaf allocations, got $allocations over 300 receives",
                )
            }
        }
}
