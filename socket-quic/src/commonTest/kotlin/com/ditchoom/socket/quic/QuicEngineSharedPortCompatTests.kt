@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [QuicPortBinding] replaced the `bind(port, host, …)` parameters, and the old form survives as a
 * deprecated convenience that delegates. This pins the two things that promise rests on: an existing
 * **call site** still compiles and still binds the port it asked for, and an engine that cannot serve
 * a shared port says so through its capabilities rather than through a surprise.
 */
class QuicEngineSharedPortCompatTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"))
    private val tls = QuicTlsConfig(certChainPath = "unused", privKeyPath = "unused")

    /** An engine implementing only the binding form — i.e. every engine, after this change. */
    private class RecordingEngine(
        override val capabilities: EngineCapabilities =
            EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = true),
    ) : QuicEngine {
        var lastBinding: QuicPortBinding? = null

        override suspend fun connect(
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            transport: com.ditchoom.socket.TransportConfig,
            timeout: Duration,
        ): QuicConnection = throw UnsupportedOperationException("client not needed here")

        override suspend fun bind(
            binding: QuicPortBinding,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: Duration,
        ): QuicServer {
            lastBinding = binding
            if (binding is QuicPortBinding.Shared && !capabilities.supportsSharedPort) {
                throw UnsupportedOperationException("this engine cannot serve a shared UDP port")
            }
            return StubServer(if (binding is QuicPortBinding.Own) binding.port else 443)
        }
    }

    private class StubServer(
        override val port: Int,
    ) : QuicServer {
        override suspend fun connections(handler: suspend QuicScope.() -> Unit) = Unit

        override suspend fun close() = Unit
    }

    /** A channel that exists only to be named in a [QuicPortBinding.Shared]; nothing reads it. */
    private class UnusedChannel : AddressedDatagramChannel {
        override val localAddress: SocketAddress = SocketAddress.ofLiteral("127.0.0.1", 443)
        override val isOpen = true
        override val capabilities = DatagramCapabilities()
        override val maxWritableSize = 1200

        override suspend fun receive(): DatagramReadResult = DatagramReadResult.Closed()

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) = Unit

        override fun close() = Unit
    }

    /**
     * The deprecated call still compiles — that is half the point of this test existing — and lands
     * on the binding form as [QuicPortBinding.Own], carrying the same port and host.
     */
    @Test
    @Suppress("DEPRECATION")
    fun theDeprecatedPortHostCallStillBindsThatPort() =
        runQuicTest {
            val engine = RecordingEngine()
            val server = engine.bind(8443, "127.0.0.1", tls, options, 5.seconds)

            assertEquals(QuicPortBinding.Own(8443, "127.0.0.1"), engine.lastBinding)
            assertEquals(8443, server.port)
        }

    /** A shared port is refused by capability, not discovered by exception. */
    @Test
    fun anEngineWithoutSharedPortSupportAdvertisesIt() =
        runQuicTest {
            val engine = RecordingEngine()
            assertFalse(
                engine.capabilities.supportsSharedPort,
                "an engine that cannot serve a shared port must advertise that, not be found out by throwing",
            )
            assertFailsWith<UnsupportedOperationException> {
                engine.bind(QuicPortBinding.Shared(UnusedChannel()), tls, options, 5.seconds)
            }
        }
}
