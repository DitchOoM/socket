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
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * A connect pinned to a radio the stack cannot pin is refused, typed, and a refusal or a radio that never appeared
 * is not retried at the peer's other addresses.
 */
class PinnedRadioTests {
    private val pinned = QuicOptions(alpnProtocols = listOf("test"), migration = MigrationPolicy.Pinned(NetworkKind.Cellular))

    private fun capabilities(vararg radios: NetworkKind.Radio) =
        EngineCapabilities(supportsMigration = true, supportsDatagrams = true, supportsServer = true, pinnableRadios = radios.toSet())

    @Test
    fun aStackThatCannotPinIsRefusedWithTheRadio() {
        val refused =
            assertFailsWith<QuicRefusedException> {
                capabilities(NetworkKind.Wifi).requirePinnable(QuicClientBinding.OwnSocket, pinned)
            }
        assertEquals<QuicRefusal>(
            QuicRefusal.RadioNotPinnable(NetworkKind.Cellular, PinnedRadioRefusal.EngineCannotPin),
            refused.refusal,
        )
    }

    @Test
    fun aStackThatCanPinDialsThePinnedConnectOnce() =
        runQuicTest {
            capabilities(NetworkKind.Wifi, NetworkKind.Cellular).requirePinnable(QuicClientBinding.OwnSocket, pinned)
            val endpoint = QuicEndpoint(ResolvedAddress("192.0.2.1", IpFamily.V4), 443)
            var attempts = 0
            assertFailsWith<UnsupportedOperationException> {
                QuicPeer.Candidates(listOf(endpoint), "example.test").raceCandidates<Unit>(TransportConfig(), close = { }) {
                    attempts++
                    throw UnsupportedOperationException("no connection is established here")
                }
            }
            assertEquals(1, attempts)
        }

    @Test
    fun aSharedPortCannotBePinned() {
        val refused =
            assertFailsWith<QuicRefusedException> {
                capabilities(NetworkKind.Cellular).requirePinnable(QuicClientBinding.Shared(UnusedChannel()), pinned)
            }
        assertEquals<QuicRefusal>(QuicRefusal.RadioNotPinnable(NetworkKind.Cellular, PinnedRadioRefusal.SharedPort), refused.refusal)
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
        assertEquals(AttemptVerdict.Fatal, quicConnectVerdict(notPinnable(radio, PinnedRadioRefusal.EngineCannotPin)))
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
