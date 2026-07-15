@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-specific tests for [JvmQuicConnection] integration with [QuicheDriver].
 * Driver-level tests are in commonTest/ReactiveDriverTests.
 */
class JvmQuicConnectionTests {
    private val bufferFactory = BufferFactory.Default
    private val testPeer = SocketAddress.ofLiteral("127.0.0.1", 4433)

    @Test
    fun jvmConnection_openStream_succeeds() =
        runBlocking(Dispatchers.IO) {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            val connScope = CoroutineScope(coroutineContext + SupervisorJob())
            val conn = JvmQuicConnection(driver, bufferFactory, testPeer, connScope)
            conn.start()

            try {
                val stream = conn.openStream()
                assertNotNull(stream)
                assertTrue(stream.isOpen)
                assertEquals(QuicStreamId(0), stream.streamId)

                val stream2 = conn.openStream()
                assertEquals(QuicStreamId(4), stream2.streamId)
            } finally {
                conn.close()
            }
        }

    @Test
    fun jvmConnection_openUniStream_usesUniIdSpace_independentOfBidi() =
        runBlocking(Dispatchers.IO) {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            val connScope = CoroutineScope(coroutineContext + SupervisorJob())
            val conn = JvmQuicConnection(driver, bufferFactory, testPeer, connScope)
            conn.start()

            try {
                // Client-initiated uni IDs are 2, 6, 10, ... (low 2 bits 0b10).
                val uni = conn.openUniStream()
                assertTrue(uni.isOpen)
                assertEquals(QuicStreamId(2), uni.streamId)
                assertTrue(uni.streamId.isUnidirectional)
                assertEquals(QuicStreamId(6), conn.openUniStream().streamId)

                // The bidi counter is independent — first bidi is still 0.
                val bidi = conn.openStream()
                assertEquals(QuicStreamId(0), bidi.streamId)
                assertTrue(bidi.streamId.isBidirectional)

                // ...and uni continues from where it left off.
                assertEquals(QuicStreamId(10), conn.openUniStream().streamId)
            } finally {
                conn.close()
            }
        }

    @Test
    fun jvmConnection_openStream_throwsAfterClose(): Unit =
        runBlocking(Dispatchers.IO) {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            val connScope = CoroutineScope(coroutineContext + SupervisorJob())
            val conn = JvmQuicConnection(driver, bufferFactory, testPeer, connScope)
            conn.start()

            conn.close()

            assertFailsWith<SocketClosedException> {
                conn.openStream()
            }
            Unit
        }

    private fun createTestDriver(api: StubQuicheApi = StubQuicheApi()): QuicheDriver {
        val channel = DatagramChannel.open().apply { configureBlocking(false) }
        return QuicheDriver(
            api = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = NioUdpChannel(channel),
            clientMode = false,
            isServer = false,
        )
    }
}
