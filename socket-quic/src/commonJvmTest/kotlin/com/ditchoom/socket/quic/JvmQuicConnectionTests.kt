package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
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

    @Test
    fun jvmConnection_openStream_succeeds() =
        runBlocking(Dispatchers.IO) {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            val connScope = CoroutineScope(coroutineContext + SupervisorJob())
            val conn = JvmQuicConnection(driver, bufferFactory, connScope)
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
    fun jvmConnection_openStream_throwsAfterClose(): Unit =
        runBlocking(Dispatchers.IO) {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            val connScope = CoroutineScope(coroutineContext + SupervisorJob())
            val conn = JvmQuicConnection(driver, bufferFactory, connScope)
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
