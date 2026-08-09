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
 * Adding shared-port support to [QuicEngine] must not break an engine that predates it. That promise
 * is only worth as much as a test that a *legacy* engine — one implementing solely the own-port
 * [QuicEngine.bind], as every engine did before [QuicPortBinding] existed — still compiles here and
 * still serves its own port.
 *
 * The class below is that engine. It does not override the [QuicPortBinding] overload at all; if the
 * overload were ever made abstract, this file stops compiling, which is exactly the alarm wanted.
 */
class QuicEngineSharedPortCompatTests {
    private val options = QuicOptions(alpnProtocols = listOf("legacy"))
    private val tls = QuicTlsConfig(certChainPath = "unused", privKeyPath = "unused")

    private class LegacyEngine : QuicEngine {
        // Written before shared ports existed, so it cannot advertise one — and the default for
        // supportsSharedPort means it does not have to say anything to be honest about it.
        override val capabilities =
            EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = true)

        var boundPort: Int? = null
        var boundHost: String? = null

        override suspend fun connect(
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            transport: com.ditchoom.socket.TransportConfig,
            timeout: Duration,
        ): QuicConnection = throw UnsupportedOperationException("client not needed here")

        override suspend fun bind(
            port: Int,
            host: String?,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: Duration,
        ): QuicServer {
            boundPort = port
            boundHost = host
            return StubServer(port)
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

    /** The general form routes an owned port to the method a legacy engine already implements. */
    @Test
    fun ownBindingReachesALegacyEnginesOwnPortBind() =
        runQuicTest {
            val engine = LegacyEngine()
            val server = engine.bind(QuicPortBinding.Own(port = 8443, host = "127.0.0.1"), tls, options, 5.seconds)

            assertEquals(8443, engine.boundPort, "Own must be unwrapped into the port/host bind")
            assertEquals("127.0.0.1", engine.boundHost)
            assertEquals(8443, server.port)
        }

    /**
     * A shared port is the one thing such an engine genuinely cannot do — so it says so up front via
     * the capability, and the attempt fails loudly rather than binding something surprising.
     */
    @Test
    fun sharedBindingIsRefusedAndAdvertisedAsUnsupported() =
        runQuicTest {
            val engine = LegacyEngine()
            assertFalse(
                engine.capabilities.supportsSharedPort,
                "an engine that cannot serve a shared port must advertise that, not be discovered by throwing",
            )
            assertFailsWith<UnsupportedOperationException> {
                engine.bind(QuicPortBinding.Shared(UnusedChannel()), tls, options, 5.seconds)
            }
            assertEquals(null, engine.boundPort, "a refused shared bind must not have bound anything")
        }
}
