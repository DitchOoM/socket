package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.OutboundDatagram
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The datagram trichotomy conformance contract (buffer-flow's `DatagramChannelConformanceTests`) run
 * against **real** JVM/Android NIO sockets on loopback — RFC Phase 2's "re-run the Phase 1 conformance
 * suite against real sockets." The in-memory baseline is green; this proves the [NioDatagramChannel]
 * actual satisfies the same contract.
 *
 * Every channel binds to `127.0.0.1` so its [DatagramChannel.localAddress] is a routable loopback
 * endpoint usable as a send target (a wildcard bind would report `0.0.0.0`, which is not addressable).
 */
@OptIn(ExperimentalDatagramApi::class)
class UdpConformanceTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bind(): DatagramChannel = UdpSocket.bind("127.0.0.1", 0).also { opened.add(it) }

    private fun DatagramChannel.addr(): SocketAddress = localAddress!!

    private fun payload(text: String): ReadBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private fun DatagramReadResult.text(): String {
        val d = assertIs<DatagramReadResult.Received>(this).datagram
        return d.payload.readByteArray(d.payload.remaining()).decodeToString()
    }

    // withTimeout so a contract violation surfaces as a fast failure instead of hanging CI on a
    // never-arriving datagram. A timed-out receive cancels select() WITHOUT closing the socket.
    private suspend fun DatagramChannel.recv(timeoutMs: Long = 5_000): DatagramReadResult = withTimeout(timeoutMs) { receive() }

    // Outer timeout bounds every test so a hung receive fails the run instead of stalling CI.
    private fun udpTest(body: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(20_000) { body() } }

    // ---- shape: pre-framed, addressed, unreliable ----

    @Test
    fun receivePreservesDatagramBoundaries() =
        udpTest {
            val a = bind()
            val b = bind()
            b.send(payload("one"), to = a.addr())
            b.send(payload("two"), to = a.addr())
            // Two sends → two whole datagrams, never concatenated into "onetwo".
            assertEquals("one", a.recv().text())
            assertEquals("two", a.recv().text())
        }

    @Test
    fun unconnectedReceiveCarriesPerPacketPeer() =
        udpTest {
            val a = bind()
            val b = bind()
            b.send(payload("hi"), to = a.addr())
            val d = assertIs<DatagramReadResult.Received>(a.recv()).datagram
            assertEquals(b.addr(), d.peer)
        }

    @Test
    fun unconnectedSendRoutesByDestination() =
        udpTest {
            val a = bind()
            val b = bind()
            val c = bind()
            a.send(payload("toB"), to = b.addr())
            a.send(payload("toC"), to = c.addr())
            assertEquals("toB", b.recv().text())
            assertEquals("toC", c.recv().text())
        }

    @Test
    fun datagramToUnboundDestinationIsDropped() =
        udpTest {
            val a = bind()
            // Nothing bound at this loopback port — unreliable drop, must not throw.
            a.send(payload("void"), to = UdpSocket.resolve("127.0.0.1", 1))
        }

    @Test
    fun connectedSendUsesFixedPeerBothDirections() =
        udpTest {
            val server = bind()
            // Connect a client to the server's address; send(to = null) targets the fixed peer.
            val client = UdpSocket.connect("127.0.0.1", server.addr().port, "127.0.0.1", 0).also { opened.add(it) }
            client.send(payload("ping")) // to = null → connected peer (server)
            val atServer = assertIs<DatagramReadResult.Received>(server.recv()).datagram
            assertEquals("ping", atServer.payload.readByteArray(atServer.payload.remaining()).decodeToString())
            assertEquals(client.addr(), atServer.peer)

            // Server replies to the client's observed source; the connected client receives it.
            server.send(payload("pong"), to = client.addr())
            val atClient = assertIs<DatagramReadResult.Received>(client.recv()).datagram
            assertEquals(server.addr(), atClient.peer)
            assertEquals("pong", atClient.payload.readByteArray(atClient.payload.remaining()).decodeToString())
        }

    // ---- lifecycle ----

    @Test
    fun closeMakesReceiveReturnClosedAndIsOpenFalse() =
        udpTest {
            val a = bind()
            assertTrue(a.isOpen)
            a.close()
            assertFalse(a.isOpen)
            assertIs<DatagramReadResult.Closed>(a.recv())
        }

    @Test
    fun sendAfterCloseThrows() =
        udpTest {
            val a = bind()
            val b = bind()
            a.close()
            assertFailsWith<IllegalStateException> { a.send(payload("x"), to = b.addr()) }
        }

    @Test
    fun sendDoesNotConsumeCallerBuffer() =
        udpTest {
            val a = bind()
            val b = bind()
            val buf = payload("keep")
            val before = buf.remaining()
            a.send(buf, to = b.addr())
            assertEquals(before, buf.remaining(), "send must not consume the caller's payload buffer")
            assertEquals("keep", b.recv().text())
        }

    @Test
    fun maxWritableSizeIsPositive() =
        udpTest {
            assertTrue(bind().maxWritableSize > 0)
        }

    // ---- control plane (capability-gated §7.2; JVM NIO row of the §7.1 matrix) ----

    @Test
    fun capabilitiesMatchTheJvmNioCeiling() =
        udpTest {
            val caps = bind().capabilities
            // Send-side: IP_TOS (ECN + DSCP) and IP_DONTFRAGMENT are standard on JVM/Android NIO.
            assertTrue(caps.ecnSend, "IP_TOS should be supported")
            assertTrue(caps.dscpSend, "IP_TOS should be supported")
            assertTrue(caps.dontFragment, "IP_DONTFRAGMENT should be supported at the JDK-21 floor")
            // Receive-side ancillary data is unreachable through NIO — firmly absent (§7.1).
            assertFalse(caps.ecnReceive)
            assertFalse(caps.hopLimitReceive)
            assertFalse(caps.localAddressReceive)
            assertFalse(caps.sourceAddressSelect)
            // No unicast TTL option on NIO (only multicast TTL).
            assertFalse(caps.hopLimitSend)
            // Multicast is design-for/defer (§10.3).
            assertFalse(caps.multicast)
        }

    @Test
    fun controlPlaneDegradesToSentinelsWhenAbsent() =
        udpTest {
            val a = bind()
            val b = bind()
            // Send with a full control plane; NIO cannot report any of it on receive → sentinels.
            b.send(
                payload("cp"),
                to = a.addr(),
                options = DatagramSendOptions(ecn = Ecn.Ect0, hopLimit = 55, fromLocal = b.addr()),
            )
            val d = assertIs<DatagramReadResult.Received>(a.recv()).datagram
            assertEquals(Ecn.Unknown, d.ecn)
            assertEquals(-1, d.hopLimit)
            assertEquals(null, d.localAddress)
        }

    @Test
    fun fullSendControlPlaneIsAppliedWithoutThrowingAndDelivers() =
        udpTest {
            val a = bind()
            val b = bind()
            // ECN + DSCP (IP_TOS) and DF (IP_DONTFRAGMENT) all set — the socket options are applied and
            // the datagram still delivers. (JVM can't read them back; delivery + no-throw is the check.)
            b.send(
                payload("opts"),
                to = a.addr(),
                options = DatagramSendOptions(ecn = Ecn.Ect0, dscp = 46, dontFragment = true, hopLimit = 10),
            )
            assertEquals("opts", a.recv().text())
        }

    // ---- batching hooks (§10.5) ----

    @Test
    fun sendBatchDefaultFansOut() =
        udpTest {
            val a = bind()
            val b = bind()
            a.sendBatch(
                listOf(
                    OutboundDatagram(payload("1"), to = b.addr()),
                    OutboundDatagram(payload("2"), to = b.addr()),
                    OutboundDatagram(payload("3"), to = b.addr()),
                ),
            )
            assertEquals("1", b.recv().text())
            assertEquals("2", b.recv().text())
            assertEquals("3", b.recv().text())
        }

    @Test
    fun receiveBatchDefaultLoops() =
        udpTest {
            val a = bind()
            val b = bind()
            repeat(3) { b.send(payload("m$it"), to = a.addr()) }
            val batch = a.receiveBatch(3)
            assertEquals(3, batch.size)
            assertEquals(listOf("m0", "m1", "m2"), batch.map { it.text() })
        }

    @Test
    fun receiveBatchStopsAtClose() =
        udpTest {
            // On a real socket close() discards the kernel buffer, so the batch terminates at the first
            // Closed (a datagram queued before close is gone). The contract point — Closed ends the
            // batch early — still holds.
            val a = bind()
            a.close()
            val batch = a.receiveBatch(5)
            assertTrue(batch.isNotEmpty())
            assertIs<DatagramReadResult.Closed>(batch.last())
            assertTrue(batch.size <= 1)
        }

    // ---- zero-alloc many-dest send target (RFC §4) ----

    @Test
    fun sendTargetOfManyDistinctPeersIsAFieldReadNotReconstruction() =
        udpTest {
            // Resolved addresses own their InetSocketAddress, so extracting the send target is a field
            // read regardless of how many distinct destinations there are — no 1-entry cache needed.
            val a = bind()
            val peers = (0 until 64).map { UdpSocket.resolve("127.0.0.1", 20_000 + it) }
            // All extractions hit the InternedJvmSocketAddress fast path (no exception, no DNS).
            peers.forEach { assertIs<InternedJvmSocketAddress>(it) }
            // And sending to each distinct (unbound) peer is a non-throwing drop.
            peers.forEach { a.send(payload("x"), to = it) }
        }
}
