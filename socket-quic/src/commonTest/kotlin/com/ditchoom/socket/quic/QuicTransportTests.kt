package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class QuicTransportTests {
    /**
     * A fake [QuicEngine] that returns a pre-configured [MockQuicConnection].
     * Verifies that [QuicTransport.connect] correctly delegates to the engine
     * and opens a stream.
     */
    private class FakeQuicEngine(
        private val connection: MockQuicConnection,
    ) : QuicEngine {
        var connectCalled = false
            private set
        var lastHostname: String? = null
            private set
        var lastPort: Int? = null
            private set

        override suspend fun connect(
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            connectionOptions: ConnectionOptions,
            timeout: Duration,
        ): QuicConnection {
            connectCalled = true
            lastHostname = hostname
            lastPort = port
            return connection
        }

        override fun close() {}
    }

    @Test
    fun connect_delegatesToEngine_andOpensStream() =
        runTest {
            val mockConn = MockQuicConnection()
            val engine = FakeQuicEngine(mockConn)
            val transport =
                QuicTransport(
                    quicOptions = QuicOptions(alpnProtocols = listOf("h3")),
                    engine = engine,
                )

            val byteStream = transport.connect("example.com", 443)

            kotlin.test.assertTrue(engine.connectCalled)
            kotlin.test.assertEquals("example.com", engine.lastHostname)
            kotlin.test.assertEquals(443, engine.lastPort)
            kotlin.test.assertTrue(byteStream.isOpen)

            byteStream.close()
        }

    @Test
    fun connect_returnedStream_canReadAndWrite() =
        runTest {
            val mockConn = MockQuicConnection()
            val engine = FakeQuicEngine(mockConn)
            val transport =
                QuicTransport(
                    quicOptions = QuicOptions(alpnProtocols = listOf("h3")),
                    engine = engine,
                )

            val stream = transport.connect("example.com", 443)
            assertIs<QuicByteStream>(stream)

            // Get the peer side to exchange data
            val peerStream = mockConn.peerStreams[QuicStreamId(0)]!!

            // Write from peer, read from stream
            val buf = BufferFactory.Default.allocate(4)
            buf.writeBytes("test".encodeToByteArray())
            buf.resetForRead()
            peerStream.write(buf, 5.seconds)

            val result = stream.read(5.seconds)
            assertIs<ReadResult.Data>(result)

            stream.close()
        }

    @Test
    fun connect_passesConnectionOptions() =
        runTest {
            val mockConn = MockQuicConnection()
            val engine = FakeQuicEngine(mockConn)
            val options = QuicOptions(alpnProtocols = listOf("h3"))
            val transport = QuicTransport(quicOptions = options, engine = engine)

            val connOptions = ConnectionOptions(connectionTimeout = 30.seconds)
            transport.connect("example.com", 443, connOptions)

            kotlin.test.assertTrue(engine.connectCalled)
        }
}
