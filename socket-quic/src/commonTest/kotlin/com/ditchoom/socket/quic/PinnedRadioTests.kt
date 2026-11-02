@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.AttemptVerdict
import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.NameResolution
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A connect pinned to a radio the engine cannot pin is refused, typed, before anything is resolved or
 * dialled — and a refusal or a radio that never appeared is not retried at the peer's other addresses.
 */
class PinnedRadioTests {
    private val pinned = QuicOptions(alpnProtocols = listOf("test"), migration = MigrationPolicy.Pinned(NetworkKind.Cellular))

    /** Counts what a connect reached: a resolution, and an engine attempt. */
    private class RecordingEngine(
        override val capabilities: EngineCapabilities,
    ) : QuicEngine {
        var attempts = 0

        override suspend fun connect(
            binding: QuicClientBinding,
            endpoint: QuicEndpoint,
            serverName: String,
            quicOptions: QuicOptions,
            transport: TransportConfig,
            timeout: Duration,
        ): QuicConnection {
            attempts++
            throw UnsupportedOperationException("no connection is established here")
        }

        override suspend fun bind(
            binding: QuicPortBinding,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: Duration,
        ): QuicServer = throw UnsupportedOperationException("not a server")
    }

    private fun capabilities(vararg radios: NetworkKind.Radio) =
        EngineCapabilities(supportsMigration = true, supportsDatagrams = true, supportsServer = true, pinnableRadios = radios.toSet())

    @Test
    fun anEngineThatCannotPinIsRefusedBeforeTheNameIsResolvedOrDialled() =
        runQuicTest {
            var resolutions = 0
            val transport =
                TransportConfig(
                    nameResolution =
                        NameResolution.Via(
                            HostResolver {
                                resolutions++
                                error("a refused connect must not resolve")
                            },
                        ),
                )
            val engine = RecordingEngine(capabilities(NetworkKind.Wifi))
            val refused =
                assertFailsWith<PinnedRadioRefusedException> {
                    engine.connect(QuicClientBinding.OwnSocket, QuicPeer.Named("example.test", 443), pinned, transport, 5.seconds)
                }
            assertEquals(NetworkKind.Cellular, refused.radio)
            assertEquals(PinnedRadioRefusal.EngineCannotPin, refused.refusal)
            assertEquals(0, resolutions)
            assertEquals(0, engine.attempts)
        }

    @Test
    fun anEngineThatCanPinDialsThePinnedConnect() =
        runQuicTest {
            val engine = RecordingEngine(capabilities(NetworkKind.Wifi, NetworkKind.Cellular))
            val endpoint = QuicEndpoint(ResolvedAddress("192.0.2.1", IpFamily.V4), 443)
            assertFailsWith<UnsupportedOperationException> {
                engine.connect(
                    QuicClientBinding.OwnSocket,
                    QuicPeer.Candidates(listOf(endpoint), "example.test"),
                    pinned,
                    TransportConfig(),
                    5.seconds,
                )
            }
            assertEquals(1, engine.attempts)
        }

    @Test
    fun aSharedPortCannotBePinned() {
        val refused =
            assertFailsWith<PinnedRadioRefusedException> {
                capabilities(NetworkKind.Cellular).requirePinnable(QuicClientBinding.Shared(UnusedChannel()), pinned)
            }
        assertEquals(PinnedRadioRefusal.SharedPort, refused.refusal)
    }

    @Test
    fun anUnpinnedConnectNeedsNoPinningCapability() {
        for (policy in listOf(MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic)) {
            capabilities().requirePinnable(QuicClientBinding.Shared(UnusedChannel()), pinned.copy(migration = policy))
        }
    }

    @Test
    fun onlyAPathTheRadioLacksToOneAddressIsWorthAnotherCandidate() {
        val radio = NetworkKind.Wifi
        assertEquals(AttemptVerdict.Fatal, quicConnectVerdict(PinnedRadioRefusedException(radio, PinnedRadioRefusal.EngineCannotPin)))
        assertEquals(
            AttemptVerdict.TryOthers,
            quicConnectVerdict(PinnedRadioUnavailableException(radio, PinnedRadioUnavailable.NoPath(RuntimeException("unsatisfied")))),
        )
        for (sameEverywhere in listOf(
            PinnedRadioUnavailable.NotAttached(5.seconds),
            PinnedRadioUnavailable.NotPermitted(RuntimeException("no CHANGE_NETWORK_STATE")),
            PinnedRadioUnavailable.NoPlatformContext,
        )) {
            assertEquals(
                AttemptVerdict.Fatal,
                quicConnectVerdict(PinnedRadioUnavailableException(radio, sameEverywhere)),
                "$sameEverywhere",
            )
        }
    }

    /** A channel that exists only to be named in a [QuicClientBinding.Shared]; nothing reads it. */
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
}
