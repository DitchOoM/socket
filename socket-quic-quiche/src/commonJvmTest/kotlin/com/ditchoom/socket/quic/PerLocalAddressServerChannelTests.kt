@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The composite that makes a wildcard server answer from the address the client dialled (#556).
 *
 * These drive [PerLocalAddressServerChannel] over fake sockets rather than a real bind, because the
 * behaviour under test is a *routing* decision — which socket a reply leaves by — and a real bind
 * would make that decision invisible: on a host where the kernel happens to choose correctly (Linux)
 * a broken composite passes, which is exactly how #556 hid on every Linux lane for the life of the
 * project. The end-to-end proof lives in `QuicMigrationLoopbackTests`, on a host with the loopback
 * alias; this is the part that holds on every host.
 */
class PerLocalAddressServerChannelTests {
    /**
     * The gate. A reply must leave by the socket its request arrived on.
     *
     * RED against a composite that always sends on the first member — which is what a wildcard bind
     * effectively does, and is the defect.
     */
    @Test
    fun aReplyLeavesBySocketTheRequestArrivedOn() =
        runBlocking {
            val loopback = FakeSocket("127.0.0.1", 4433)
            val alias = FakeSocket("127.0.0.2", 4433)
            val channel = PerLocalAddressServerChannel.of(listOf(loopback, alias))

            val peer = literal("198.51.100.7", 51000)
            alias.deliver(peer)
            withTimeout(5.seconds) { channel.receive() }

            channel.send(payload(), peer)

            assertEquals(1, alias.sent.size, "the reply must go out of the socket bound to the dialled address")
            assertEquals(0, loopback.sent.size, "no reply may leave by an address the client never dialled")
            channel.close()
        }

    /** The received datagram carries the address it was addressed to — the wildcard could not know it. */
    @Test
    fun aReceivedDatagramCarriesTheLocalAddressItWasSentTo() =
        runBlocking {
            val loopback = FakeSocket("127.0.0.1", 4433)
            val alias = FakeSocket("127.0.0.2", 4433)
            val channel = PerLocalAddressServerChannel.of(listOf(loopback, alias))

            alias.deliver(literal("198.51.100.7", 51000))
            val result = withTimeout(5.seconds) { channel.receive() }

            val received = result as DatagramReadResult.Received
            val local = received.datagram.localAddress.orNull()
            assertEquals("127.0.0.2", local?.host, "localAddress must name the socket the datagram arrived on")
            channel.close()
        }

    /**
     * The composite advertises the capability it actually implements.
     *
     * Not a claim about the members — each still reports false. The composite can genuinely answer
     * both questions, and a caller that consults the flag (which is what it is for) must get the
     * composite's answer, not a member's.
     */
    @Test
    fun theCompositeAdvertisesWhatItImplementsNotWhatItsMembersDo() =
        runBlocking {
            val members = listOf(FakeSocket("127.0.0.1", 4433), FakeSocket("127.0.0.2", 4433))
            assertTrue(members.none { it.capabilities.sourceAddressSelect }, "the fake models NIO: no cmsg")

            val channel = PerLocalAddressServerChannel.of(members)
            assertTrue(channel.capabilities.localAddressReceive)
            assertTrue(channel.capabilities.sourceAddressSelect)
            channel.close()
        }

    /** An explicit `fromLocal` is honoured — an ICE/multi-homed caller may name the source itself. */
    @Test
    fun anExplicitFromLocalChoosesThatSocket() =
        runBlocking {
            val loopback = FakeSocket("127.0.0.1", 4433)
            val alias = FakeSocket("127.0.0.2", 4433)
            val channel = PerLocalAddressServerChannel.of(listOf(loopback, alias))

            val peer = literal("198.51.100.7", 51000)
            alias.deliver(peer)
            withTimeout(5.seconds) { channel.receive() }

            // The peer's own route says `alias`; the explicit request must win over it.
            channel.send(payload(), peer, DatagramSendOptions(fromLocal = literal("127.0.0.1", 4433)))

            assertEquals(1, loopback.sent.size, "an explicit fromLocal must pick that socket")
            assertEquals(0, alias.sent.size)
            assertTrue(
                loopback.sent
                    .single()
                    .second.fromLocal == null,
                "fromLocal is consumed by choosing the socket; passing it to a member that ignores it would be a lie",
            )
            channel.close()
        }

    /** Closing the composite closes every member — a leaked socket holds the port. */
    @Test
    fun closingClosesEveryMember() =
        runBlocking {
            val members = listOf(FakeSocket("127.0.0.1", 4433), FakeSocket("127.0.0.2", 4433))
            PerLocalAddressServerChannel.of(members).close()
            assertTrue(members.all { it.closed }, "every member socket must be closed")
        }

    /** One address needs no composite, and paying a coroutine hand-off per datagram for it would be waste. */
    @Test
    fun aSingleAddressIsNotWrapped() {
        val only = FakeSocket("127.0.0.1", 4433)
        assertTrue(PerLocalAddressServerChannel.of(listOf(only)) === only)
    }

    private fun payload(): ReadBuffer = BufferFactory.Default.allocate(4).also { it.resetForRead() }

    private fun literal(
        host: String,
        port: Int,
    ): SocketAddress = SocketAddress.ofLiteral(host, port)

    /**
     * A socket bound to one address that records what it was asked to send.
     *
     * Models NIO deliberately — `sourceAddressSelect = false` — because the composite exists exactly
     * for backends that cannot pin a source themselves.
     */
    private class FakeSocket(
        host: String,
        port: Int,
    ) : AddressedDatagramChannel {
        override val localAddress: SocketAddress = SocketAddress.ofLiteral(host, port)
        val sent = mutableListOf<Pair<SocketAddress, DatagramSendOptions>>()
        var closed = false
            private set

        private val queue = Channel<DatagramReadResult>(Channel.UNLIMITED)

        fun deliver(peer: SocketAddress) {
            val buf: PlatformBuffer = BufferFactory.Default.allocate(4)
            buf.resetForRead()
            queue.trySend(DatagramReadResult.Received(Datagram(payload = buf, peer = peer)))
        }

        override val isOpen: Boolean get() = !closed
        override val maxWritableSize: Int = 1200
        override val capabilities: DatagramCapabilities = DatagramCapabilities()

        override suspend fun receive(): DatagramReadResult = queue.receive()

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) {
            sent += to to options
        }

        override fun close() {
            closed = true
        }
    }
}
