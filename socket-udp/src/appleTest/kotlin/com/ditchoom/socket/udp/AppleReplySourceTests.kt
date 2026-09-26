@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A wildcard-bound Apple socket reports the address each datagram was sent to, and a send that names
 * a source leaves from it — judged by the **receiver's** `recvfrom`, never by what the sender says.
 *
 * The pairs are a loopback client dialling one of this host's interface addresses. Darwin answers a
 * wildcard socket's reply to a local destination *from that destination*: a client on `127.0.0.1`
 * that dialled the interface address gets its reply from `127.0.0.1` unless the source is pinned. So
 * each positive case here is red against a socket that leaves the choice to the kernel, on a host with
 * no alias configured.
 */
class AppleReplySourceTests {
    private val opened = mutableListOf<AddressedDatagramChannel>()
    private val factory = BufferFactory.deterministic()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    @Test
    fun theWildcardSocketAdvertisesBothHalvesOfSourceSelection() =
        runBlocking<Unit> {
            val caps = bind(null).capabilities
            assertTrue(caps.localAddressReceive, "each datagram must report the address it was sent to")
            assertTrue(caps.sourceAddressSelect, "a send must be able to name the source it leaves from")
        }

    @Test
    fun aWildcardSocketReportsTheAddressEachDatagramWasSentTo() =
        runBlocking<Unit> {
            val server = bind(null)
            val port = server.localAddress.port
            for ((client, dialled) in pairs()) {
                val sender = bind(client)
                sender.send(payload(), resolve(dialled, port))
                val arrived = assertNotNull(server.receiveWithin(RECEIVE_WINDOW), "$client -> $dialled: nothing arrived")
                val local = arrived.localAddress.orNull()
                arrived.payload.freeNativeMemory()
                assertNotNull(local, "$client -> $dialled: the datagram did not report the address it was sent to")
                assertTrue(sameAddress(local, dialled), "$client -> $dialled: reported ${local.host} as the address it was sent to")
                assertEquals(port, local.port, "the reported address carries the socket's own port")
            }
        }

    @Test
    fun aReplyNamingItsSourceLeavesFromItOverIpv4() = replyLeavesFromTheDialledAddress(AddressFamily.IPv4)

    @Test
    fun aReplyNamingItsSourceLeavesFromItOverIpv6() = replyLeavesFromTheDialledAddress(AddressFamily.IPv6)

    private fun replyLeavesFromTheDialledAddress(family: AddressFamily) =
        runBlocking<Unit> {
            val (client, dialled) =
                pairs().firstOrNull { it.family == family && it.client != it.dialled }
                    ?: return@runBlocking skip("$family")
            val server = bind(null)
            val port = server.localAddress.port
            val sender = bind(client)
            sender.send(payload(), resolve(dialled, port))
            val request = assertNotNull(server.receiveWithin(RECEIVE_WINDOW), "the request never arrived")
            request.payload.freeNativeMemory()

            // The kernel's own choice, recorded so a run shows whether the pin below had anything to fix.
            server.send(payload(), request.peer)
            val unpinned = assertNotNull(sender.receiveWithin(RECEIVE_WINDOW), "the unpinned reply never arrived")
            unpinned.payload.freeNativeMemory()
            println("[AppleReplySourceTests] $client -> $dialled: an unpinned reply leaves from ${unpinned.peer.host}")

            server.send(payload(), request.peer, DatagramSendOptions(fromLocal = resolve(dialled, port)))
            val pinned = assertNotNull(sender.receiveWithin(RECEIVE_WINDOW), "the pinned reply never arrived")
            pinned.payload.freeNativeMemory()
            assertTrue(
                sameAddress(pinned.peer, dialled),
                "$client dialled $dialled; the reply naming $dialled as its source arrived from ${pinned.peer.host}",
            )
        }

    @Test
    fun aSourceTheHostDoesNotHoldIsRefusedTypedOverIpv4() = unheldSourceIsRefused("127.0.0.1", "192.0.2.1")

    @Test
    fun aSourceTheHostDoesNotHoldIsRefusedTypedOverIpv6() = unheldSourceIsRefused("::1", "2001:db8::1")

    /**
     * An unpinned reply first, so the socket holds a route to the peer: Darwin checks an `IP_PKTINFO`
     * source only when it looks a route up, and sends from any address at all once it has one.
     */
    private fun unheldSourceIsRefused(
        loopback: String,
        unheld: String,
    ) = runBlocking<Unit> {
        val server = bind(null)
        val port = server.localAddress.port
        val client = bind(loopback)
        client.send(payload(), resolve(loopback, port))
        val request = assertNotNull(server.receiveWithin(RECEIVE_WINDOW), "the request never arrived")
        request.payload.freeNativeMemory()
        server.send(payload(), request.peer)
        assertNotNull(client.receiveWithin(RECEIVE_WINDOW), "the warm-up reply never arrived").payload.freeNativeMemory()

        val refused =
            assertFailsWith<DatagramSendException>("a source this host does not hold must not be sent from") {
                server.send(payload(), request.peer, DatagramSendOptions(fromLocal = resolve(unheld, 0)))
            }
        val error = assertIs<DatagramSendError.SourceAddressUnavailable>(refused.error)
        assertIs<SourceAddressRejection.NotAssigned>(error.reason, "reported $error")
        val leaked = client.receiveWithin(SILENCE_WINDOW)
        assertNull(leaked?.peer?.host, "a datagram left from ${leaked?.peer?.host} while the send reported a refusal")
        leaked?.payload?.freeNativeMemory()
    }

    @Test
    fun aSourceOfTheOtherFamilyIsRefused() =
        runBlocking<Unit> {
            for ((loopback, other) in listOf("::1" to "127.0.0.1", "127.0.0.1" to "::1")) {
                val server = bind(null)
                val client = bind(loopback)
                client.send(payload(), resolve(loopback, server.localAddress.port))
                val request = assertNotNull(server.receiveWithin(RECEIVE_WINDOW), "the request never arrived")
                request.payload.freeNativeMemory()
                val refused =
                    assertFailsWith<DatagramSendException>("$other cannot be the source of a datagram to $loopback") {
                        server.send(payload(), request.peer, DatagramSendOptions(fromLocal = resolve(other, 0)))
                    }
                val error = assertIs<DatagramSendError.SourceAddressUnavailable>(refused.error)
                assertEquals(SourceAddressRejection.WrongFamily, error.reason)
            }
        }

    @Test
    fun aWildcardSourceNamesNoSource() =
        runBlocking<Unit> {
            for ((loopback, wildcard) in listOf("127.0.0.1" to "0.0.0.0", "::1" to "::", "127.0.0.1" to "::")) {
                val server = bind(null)
                val client = bind(loopback)
                client.send(payload(), resolve(loopback, server.localAddress.port))
                val request = assertNotNull(server.receiveWithin(RECEIVE_WINDOW), "the request never arrived")
                request.payload.freeNativeMemory()
                server.send(payload(), request.peer, DatagramSendOptions(fromLocal = resolve(wildcard, 0)))
                val reply = assertNotNull(client.receiveWithin(RECEIVE_WINDOW), "fromLocal=$wildcard: the reply never arrived")
                reply.payload.freeNativeMemory()
            }
        }

    /** One client per family and pairing: loopback to loopback, and loopback to an interface address. */
    private fun pairs(): List<Dial> {
        val v4 = HostUnicastAddresses.firstNonLoopback(AddressFamily.IPv4)
        val v6 = HostUnicastAddresses.firstNonLoopback(AddressFamily.IPv6)
        if (v4 == null) println("[AppleReplySourceTests] SKIP IPv4 interface pair: no non-loopback IPv4 address")
        if (v6 == null) println("[AppleReplySourceTests] SKIP IPv6 interface pair: no non-loopback IPv6 address")
        return listOfNotNull(
            Dial("127.0.0.1", "127.0.0.1", AddressFamily.IPv4),
            v4?.let { Dial("127.0.0.1", it, AddressFamily.IPv4) },
            Dial("::1", "::1", AddressFamily.IPv6),
            v6?.let { Dial("::1", it, AddressFamily.IPv6) },
        )
    }

    private data class Dial(
        val client: String,
        val dialled: String,
        val family: AddressFamily,
    )

    private fun skip(what: String) = println("[AppleReplySourceTests] SKIP $what: this host has no non-loopback $what address")

    private suspend fun bind(host: String?): AddressedDatagramChannel =
        UdpSocket.bind(host, 0, bufferFactory = factory).also { opened += it }

    private suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress = UdpSocket.resolve(host, port)

    private fun payload() = factory.allocate(4).also { it.writeInt(0x51554943) }.also { it.resetForRead() }

    /** The same IP, whether spelled IPv4 or as the IPv4-mapped IPv6 a dual-stack socket reports. */
    private suspend fun sameAddress(
        actual: SocketAddress,
        expected: String,
    ): Boolean = actual.canonical() == resolve(expected, 0).canonical()

    private fun SocketAddress.canonical(): Pair<Long, Long> {
        val apple = this as AppleSocketAddress
        return when (apple.family) {
            AddressFamily.IPv4 -> 0L to (0xFFFF_0000_0000L or apple.lo)
            AddressFamily.IPv6 -> apple.hi to apple.lo
        }
    }

    /** The next datagram, or `null` after [window] — closing the socket to end the wait. */
    private suspend fun AddressedDatagramChannel.receiveWithin(window: Duration): Datagram? =
        coroutineScope {
            val pending = async(Dispatchers.Default) { receive() }
            val result = withTimeoutOrNull(window) { pending.await() }
            if (result == null) {
                close()
                (pending.await() as? DatagramReadResult.Received)?.datagram?.payload?.freeNativeMemory()
                null
            } else {
                (result as? DatagramReadResult.Received)?.datagram
            }
        }

    private companion object {
        val RECEIVE_WINDOW = 5.seconds
        val SILENCE_WINDOW = 300.milliseconds
    }
}
