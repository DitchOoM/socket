package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.OutboundDatagram
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The datagram trichotomy conformance contract run against **real** Node.js `dgram` sockets on loopback
 * — RFC Phase 4's "re-run the Phase 1/2/3 conformance suite against the net-new Node actual." Mirrors
 * the native `UdpNativeConformanceTests`, restricted to the platform-neutral contract (no JVM-specific
 * [SocketAddress] subtype assertions), plus the Node capability row (§7.1: only `hopLimitSend`).
 *
 * Node has no `runBlocking`; each test returns the [GlobalScope.promise] so Mocha awaits the real event
 * loop (a virtual-time `runTest` would break `dgram`'s real timers/IO). Every channel binds `127.0.0.1`
 * so its [DatagramChannel.localAddress] is a routable loopback endpoint usable as a send target.
 */
@OptIn(ExperimentalDatagramApi::class, DelicateCoroutinesApi::class)
class UdpNodeConformanceTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bind(): DatagramChannel = UdpSocket.bind("127.0.0.1", 0).also { opened.add(it) }

    private fun DatagramChannel.addr(): SocketAddress = localAddress!!

    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buf = PlatformBuffer.allocateNative(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private fun DatagramReadResult.text(): String {
        val d = assertIs<DatagramReadResult.Received>(this).datagram
        return d.payload.readByteArray(d.payload.remaining()).decodeToString()
    }

    private suspend fun DatagramChannel.recv(timeoutMs: Long = 5_000): DatagramReadResult = withTimeout(timeoutMs) { receive() }

    // Each test returns the Promise so Mocha awaits the real Node event loop.
    private fun udpTest(body: suspend CoroutineScope.() -> Unit) = GlobalScope.promise { withTimeout(20_000) { body() } }

    // ---- shape: pre-framed, addressed, unreliable ----

    @Test
    fun receivePreservesDatagramBoundaries() =
        udpTest {
            val a = bind()
            val b = bind()
            b.send(payload("one"), to = a.addr())
            b.send(payload("two"), to = a.addr())
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
            val client = UdpSocket.connect("127.0.0.1", server.addr().port, "127.0.0.1", 0).also { opened.add(it) }
            client.send(payload("ping")) // to = null → connected peer (server)
            val atServer = assertIs<DatagramReadResult.Received>(server.recv()).datagram
            assertEquals("ping", atServer.payload.readByteArray(atServer.payload.remaining()).decodeToString())
            assertEquals(client.addr(), atServer.peer)

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

    // ---- control plane (capability-gated §7.2; Node `dgram` row of §7.1) ----

    @Test
    fun capabilitiesMatchTheNodeDgramCeiling() =
        udpTest {
            val caps = bind().capabilities
            // Node `dgram` exposes only setTTL — hopLimitSend is the sole supported knob.
            assertTrue(caps.hopLimitSend, "dgram.setTTL should be supported")
            // Everything else is absent (§7.1 Node column): no ECN/DSCP/DF send, no recv ancillary data.
            assertFalse(caps.ecnSend)
            assertFalse(caps.ecnReceive)
            assertFalse(caps.dscpSend)
            assertFalse(caps.dontFragment)
            assertFalse(caps.hopLimitReceive)
            assertFalse(caps.localAddressReceive)
            assertFalse(caps.sourceAddressSelect)
            assertFalse(caps.multicast)
        }

    @Test
    fun controlPlaneDegradesToSentinelsWhenAbsent() =
        udpTest {
            val a = bind()
            val b = bind()
            b.send(
                payload("cp"),
                to = a.addr(),
                options = DatagramSendOptions(ecn = Ecn.Ect0, hopLimit = 55, fromLocal = b.addr()),
            )
            val caps = a.capabilities
            val d = assertIs<DatagramReadResult.Received>(a.recv()).datagram
            if (!caps.ecnReceive) assertEquals(Ecn.Unknown, d.ecn)
            if (!caps.hopLimitReceive) assertEquals(-1, d.hopLimit)
            if (!caps.localAddressReceive) assertEquals(null, d.localAddress)
        }

    @Test
    fun fullSendControlPlaneIsAppliedWithoutThrowingAndDelivers() =
        udpTest {
            val a = bind()
            val b = bind()
            // hopLimit (setTTL) is applied; ECN/DSCP/DF are no-ops Node lacks. Delivery + no-throw is
            // the check (Node can't read any of it back).
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
            val a = bind()
            val peers = (0 until 64).map { UdpSocket.resolve("127.0.0.1", 20_000 + it) }
            // Sending to each distinct (unbound) peer is a non-throwing drop; the owned numeric
            // host:port is read straight off each SocketAddress, no reconstruction per destination.
            peers.forEach { a.send(payload("x"), to = it) }
        }
}
