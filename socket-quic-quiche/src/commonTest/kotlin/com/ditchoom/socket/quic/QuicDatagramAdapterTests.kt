@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic unit tests for the datagram surface ([DriverDatagramAdapter] over a [QuicheDriver]
 * driven by [StubQuicheApi]). No native lib / network — these run on every platform and exercise the
 * control flow, backpressure parking, close handling, and buffer ownership directly.
 *
 * Byte-level round-trip (real encryption over UDP) is covered by [QuicDatagramTestSuite].
 */
class QuicDatagramAdapterTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** The connection's remote peer — every received [com.ditchoom.buffer.flow.Datagram] carries it. */
    private val testPeer = SocketAddress.ofLiteral("127.0.0.1", 4433)

    private fun driverWith(
        api: StubQuicheApi,
        factory: BufferFactory = bufferFactory,
    ): QuicheDriver =
        QuicheDriver(
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = factory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
        )

    /** Suspend until the driver has published [MaxDatagramSize] from [afterCommand]. */
    private suspend fun awaitMaxSizePublished(driver: QuicheDriver) =
        withTimeout(2.seconds) {
            while (driver.lastMaxDatagramSize !is MaxDatagramSize.Bytes) yield()
        }

    @Test
    fun receiveDatagram_returnsReceived_whenDataAvailable() =
        runQuicTest {
            val api = StubQuicheApi().apply { dgramRecvResult = StreamRecvResult.Data(5, false) }
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                val result = withTimeout(2.seconds) { adapter.receive() }
                assertIs<DatagramReadResult.Received>(result)
                assertEquals(5, result.datagram.payload.remaining(), "received buffer should expose the datagram length")
                result.datagram.payload.freeIfNeeded()
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun receiveDatagram_parksOnDone_thenWakesOnSignal() =
        runQuicTest {
            // First poll returns Done (queue empty), then a datagram arrives. hasReadableDgram=true makes
            // afterCommand tickle dgramSignal, waking the parked receiver to retry and get the Data.
            val api =
                StubQuicheApi().apply {
                    hasReadableDgram = true
                    dgramRecvSequence.addAll(listOf(StreamRecvResult.Done, StreamRecvResult.Data(3, false)))
                }
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                val result = withTimeout(2.seconds) { adapter.receive() }
                assertIs<DatagramReadResult.Received>(result)
                assertEquals(3, result.datagram.payload.remaining())
                result.datagram.payload.freeIfNeeded()
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun receiveDatagram_returnsConnectionClosed_onClose() =
        runQuicTest {
            // Done + no readable signal → the receiver parks on dgramSignal; destroy() closes it.
            val api = StubQuicheApi().apply { dgramRecvResult = StreamRecvResult.Done }
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            val pending = async { adapter.receive() }
            yield() // let it park
            driver.destroy()
            val result = withTimeout(2.seconds) { pending.await() }
            assertIs<DatagramReadResult.Closed>(result)
        }

    @Test
    fun sendDatagram_throwsWhenUnavailable() =
        runQuicTest {
            val api = StubQuicheApi().apply { dgramMaxWritableLen = MaxDatagramSize.Unavailable }
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                val buf =
                    bufferFactory.allocate(4).apply {
                        writeInt(1)
                        resetForRead()
                    }
                assertFailsWith<IllegalStateException> { adapter.send(buf) }
                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun sendDatagram_succeedsWhenAvailable() =
        runQuicTest {
            val api = StubQuicheApi().apply { dgramMaxWritableLen = MaxDatagramSize.Bytes(1200) }
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                awaitMaxSizePublished(driver)
                val buf =
                    bufferFactory.allocate(5).apply {
                        repeat(5) { writeByte(it.toByte()) }
                        resetForRead()
                    }
                withTimeout(2.seconds) { adapter.send(buf) } // returns normally on success
                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun sendDatagram_throwsWhenTooLarge() =
        runQuicTest {
            val api = StubQuicheApi().apply { dgramMaxWritableLen = MaxDatagramSize.Bytes(4) }
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                awaitMaxSizePublished(driver)
                val buf =
                    bufferFactory.allocate(8).apply {
                        repeat(8) { writeByte(0) }
                        resetForRead()
                    }
                assertFailsWith<IllegalArgumentException> { adapter.send(buf) }
                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun receiveDatagram_transfersOwnership_noLeaks() =
        runQuicTest {
            // TrackingBufferFactory verifies the recv buffer's pool round-trip ends at the leaf:
            // the caller's release returns it to the driver's recvBufPool, and destroy()'s
            // pool.clear() (plus the driver's own scratch-buffer frees) releases every leaf
            // allocation — nothing leaks.
            val factory = TrackingBufferFactory()
            val api = StubQuicheApi().apply { dgramRecvResult = StreamRecvResult.Data(3, false) }
            val driver = driverWith(api, factory)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                val result = withTimeout(2.seconds) { adapter.receive() }
                assertIs<DatagramReadResult.Received>(result)
                result.datagram.payload.freeIfNeeded() // caller owns the received buffer
            } finally {
                driver.destroy()
            }
            factory.assertNoLeaks()
        }
}
