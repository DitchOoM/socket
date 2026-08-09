@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The RFC 9443 port-sharing arrangement, over an in-memory channel: QUIC and the ICE/media protocols
 * on ONE socket, each reaching its own stack.
 *
 * Every test here runs with no socket, no port and no wall-clock — [demultiplex] is defined over any
 * [AddressedDatagramChannel], which is precisely so a consumer (a sans-I/O media stack, say) can test
 * its half of a shared port the same way, driving datagrams in by hand.
 */
class MultiplexedUdpSocketTests {
    private val local = SocketAddress.ofLiteral("192.0.2.1", 443)
    private val peer = SocketAddress.ofLiteral("192.0.2.20", 51234)
    private val relay = SocketAddress.ofLiteral("192.0.2.10", 3478)

    /** A recorded send, so a test can assert which branch reached the wire and where it went. */
    private class SentDatagram(
        val firstByte: Byte,
        val to: SocketAddress,
    )

    /**
     * An in-memory [AddressedDatagramChannel]: [deliver] plays a datagram in as if the kernel had, and
     * [sent] records what went out. This is the whole test harness a shared-port consumer needs.
     */
    private class FakeChannel(
        override val localAddress: SocketAddress,
    ) : AddressedDatagramChannel {
        private val inbound = Channel<Datagram>(Channel.UNLIMITED)
        val sent = mutableListOf<SentDatagram>()
        private var open = true

        override val isOpen: Boolean get() = open
        override val capabilities: DatagramCapabilities = DatagramCapabilities()
        override val maxWritableSize: Int = MAX_UDP_DATAGRAM_SIZE

        fun deliver(
            firstByte: Byte,
            from: SocketAddress,
            payloadSize: Int = 4,
        ) {
            val payload = BufferFactory.deterministic().allocate(payloadSize)
            repeat(payloadSize) { i -> payload.writeByte(if (i == 0) firstByte else i.toByte()) }
            payload.resetForRead()
            inbound.trySend(Datagram(payload, from))
        }

        /** A zero-length datagram — legal UDP, and classifiable by nothing. */
        fun deliverEmpty(from: SocketAddress) {
            val payload = BufferFactory.deterministic().allocate(1)
            payload.resetForRead()
            payload.setLimit(0)
            inbound.trySend(Datagram(payload, from))
        }

        override suspend fun receive(): DatagramReadResult =
            inbound.receiveCatching().getOrNull()?.let { DatagramReadResult.Received(it) }
                ?: DatagramReadResult.Closed()

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) {
            val start = payload.position()
            sent += SentDatagram(payload.readByte(), to)
            payload.position(start) // a send must not consume the caller's buffer
        }

        override fun close() {
            open = false
            inbound.close()
        }
    }

    private fun buffer(firstByte: Byte): PlatformBuffer =
        BufferFactory.deterministic().allocate(1).apply {
            writeByte(firstByte)
            resetForRead()
        }

    /** The core split: QUIC bytes reach the QUIC branch, everything else reaches the consumer flow. */
    @Test
    fun quicAndMediaReachTheirOwnStacksFromOnePort() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope)

            channel.deliver(0xC0.toByte(), from = peer) // QUIC Initial (long header)
            channel.deliver(0x00, from = peer) // STUN binding
            channel.deliver(22, from = peer) // DTLS handshake
            channel.deliver(0x80.toByte(), from = peer) // RTP
            channel.deliver(0x50, from = peer) // QUIC 1-RTT (short header)

            val media = mutableListOf<MultiplexedProtocol.NonQuic>()
            val collector =
                launch {
                    mux.datagrams
                        .take(3)
                        .toList()
                        .mapTo(media) { it.protocol }
                }

            val firstQuic = assertIs<DatagramReadResult.Received>(mux.quic.receive())
            assertEquals(0xC0.toByte(), firstQuic.datagram.payload.readByte())
            val secondQuic = assertIs<DatagramReadResult.Received>(mux.quic.receive())
            assertEquals(0x50, secondQuic.datagram.payload.readByte())

            collector.join()
            assertEquals(
                listOf(MultiplexedProtocol.Stun, MultiplexedProtocol.Dtls, MultiplexedProtocol.RtpRtcp),
                media,
                "the non-QUIC branch keeps arrival order and classifies each datagram",
            )
            assertEquals(MultiplexedDropCounts(), mux.dropped.value, "nothing was dropped")
            assertEquals(local, mux.localAddress)
            assertEquals(local, mux.quic.localAddress, "both branches report the one shared port")
        }

    /** The TURN relay set is honored end to end, not just in the pure classifier. */
    @Test
    fun turnChannelDataFromTheRelayIsNotMistakenForQuic() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope, relays = TurnRelays(setOf(relay)))

            channel.deliver(0x40, from = relay) // ChannelData: TURN, because of who sent it
            channel.deliver(0x40, from = peer) // same byte from anyone else: a QUIC short header

            assertEquals(MultiplexedProtocol.TurnChannel, mux.datagrams.first().protocol)
            assertIs<DatagramReadResult.Received>(mux.quic.receive())
        }

    /**
     * Closing the QUIC listener must not take the call down with it: the QUIC branch detaches, and the
     * port keeps serving media. This is the reason the QUIC stack gets a branch and not the socket.
     */
    @Test
    fun closingTheQuicBranchLeavesThePortServingMedia() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope)

            mux.quic.close()
            runCurrent()

            assertIs<DatagramReadResult.Closed>(mux.quic.receive(), "the QUIC stack sees a closed channel")
            assertFalse(mux.quic.isOpen)
            assertTrue(channel.isOpen, "the socket itself is untouched")

            channel.deliver(0x00, from = peer) // STUN still flows
            assertEquals(MultiplexedProtocol.Stun, mux.datagrams.first().protocol)

            channel.deliver(0xC0.toByte(), from = peer) // QUIC now has nowhere to go
            runCurrent()
            assertEquals(1, mux.dropped.value.quicOverflow, "post-detach QUIC datagrams are counted, not leaked")

            mux.send(buffer(0x01), to = peer)
            assertEquals(1, channel.sent.size, "media can still reply from the shared port")
        }

    /** Closing the mux closes the socket and ends both branches — no consumer is left suspended. */
    @Test
    fun closingTheMuxEndsBothBranches() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope)

            mux.close()
            runCurrent()

            assertFalse(channel.isOpen)
            assertIs<DatagramReadResult.Closed>(mux.quic.receive())
            assertEquals(emptyList(), mux.datagrams.toList(), "the consumer flow completes instead of hanging")
        }

    /** Both stacks send through the one socket, so replies leave from the port the peer is talking to. */
    @Test
    fun bothBranchesSendThroughTheOnePort() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope)

            mux.quic.send(buffer(0xC0.toByte()), to = peer)
            mux.send(buffer(0x00), to = relay)

            assertEquals(listOf<Byte>(0xC0.toByte(), 0x00), channel.sent.map { it.firstByte })
            assertEquals(listOf(peer, relay), channel.sent.map { it.to })
        }

    /** RFC 9443 says drop the unassigned range; a zero-length datagram has nothing to classify at all. */
    @Test
    fun unroutableDatagramsAreDroppedAndCounted() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope)

            channel.deliver(0x04, from = peer) // unassigned (4..15)
            channel.deliverEmpty(from = peer)
            channel.deliver(0x00, from = peer) // a real STUN datagram behind them
            runCurrent()

            assertEquals(MultiplexedProtocol.Stun, mux.datagrams.first().protocol)
            assertEquals(2, mux.dropped.value.unroutable)
            assertEquals(0, mux.dropped.value.nonQuicOverflow)
        }

    /**
     * A consumer that stops draining must not stall the shared reader — that would turn a slow media
     * stack into a QUIC outage. The branch drops its oldest datagrams and says so.
     */
    @Test
    fun aStalledConsumerDropsOldestInsteadOfStallingTheOtherStack() =
        runTest {
            val channel = FakeChannel(local)
            val mux = channel.demultiplex(backgroundScope, branchCapacity = 2)

            repeat(5) { channel.deliver(0x00, from = peer) } // nobody is collecting the media branch
            channel.deliver(0xC0.toByte(), from = peer) // QUIC behind the pile-up
            runCurrent()

            assertEquals(3, mux.dropped.value.nonQuicOverflow, "5 delivered, 2 buffered → 3 dropped")
            assertEquals(0, mux.dropped.value.quicOverflow)
            assertIs<DatagramReadResult.Received>(
                mux.quic.receive(),
                "QUIC still got its datagram while the media branch was overflowing",
            )
        }
}
