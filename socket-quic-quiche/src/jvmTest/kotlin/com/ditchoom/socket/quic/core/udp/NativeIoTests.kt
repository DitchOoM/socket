package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.network
import org.junit.Rule
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The native stack's io against the real kernel, at every batching it offers here: a batch arrives as its datagrams,
 * each with its own length and bytes; a batch refused part way reports exactly how many datagrams went and leaves
 * the rest unsent, which is what a send lane's [SendBatch.took] relies on.
 */
class NativeIoTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val shim by lazy { loadNetShim() }
    private val owned = ArrayList<NativeBytes>()
    private val opened = ArrayList<NativeIo>()

    /** The most datagrams one receive of the last [receiveAll] returned. */
    private var widestRead = 0

    @AfterTest
    fun freeAll() {
        opened.forEach { it.close() }
        owned.forEach { it.free() }
    }

    @Test
    fun aBatchArrivesAsItsDatagramsAtEveryBatchingOnIpv4() = UdpBatching.entries.forEach { roundTrip(it, V4_LOOPBACK) }

    @Test
    fun aBatchArrivesAsItsDatagramsAtEveryBatchingOnIpv6() = UdpBatching.entries.forEach { roundTrip(it, V6_LOOPBACK) }

    @Test
    fun aBatchRefusedPartWaySaysExactlyHowManyWentAtEveryBatching() =
        UdpBatching.entries.forEach { ceiling ->
            val server = open(V4_LOOPBACK, SocketPeer.Unconnected, ceiling)
            val client = open(V4_LOOPBACK, SocketPeer.Connected(server.local), ceiling)
            val batch = sendBatch()
            repeat(6) { i ->
                // The fourth goes where an IPv4 socket cannot send: an IPv6 address.
                val to = if (i == 3) PathKey(6, 4433, 0L, 1L) else client.local
                batch.put(i, 1000, SendTarget.ServerReply(to, ReplySource.Undecodable))
            }
            val result = assertIs<SendBatchResult.Partial>(server.io.send(batch), "$ceiling")
            assertEquals(3, result.taken, "$ceiling: datagrams taken before the refusal")
            assertEquals(3, receiveAll(client.io, 3).size, "$ceiling: datagrams that arrived")
        }

    @Test
    fun everySocketOpensWithDfSetAndSaysSo() {
        assumeNativeStack()
        listOf(V4_LOOPBACK, V6_LOOPBACK).forEach { host ->
            val socket =
                assertIs<NativeOpened.Opened>(
                    NativeSocket.open(
                        shim,
                        native(NativeSocket.SPEC_BYTES),
                        InetSocketAddress(host, 0).toPathKey(),
                        SocketPeer.Unconnected,
                        1 shl 20,
                        1 shl 20,
                        UdpBatching.Newest,
                        BATCH,
                    ),
                ).socket
            assertIs<DontFragment.Set>(socket.dontFragment, host)
            socket.cell.close { fd -> shim.close(fd) }
        }
    }

    @Test
    fun theKernelsBatchingIsProbedOnceAndReported() {
        val io = open(V4_LOOPBACK, SocketPeer.Unconnected, UdpBatching.Newest).io
        val described =
            when (val capability = io.capability) {
                is BatchCapability.OneByOne -> "OneByOne"
                is BatchCapability.Multi -> "Multi(${capability.maxDatagrams})"
                is BatchCapability.Segmented ->
                    "Segmented(${capability.maxDatagrams}, gso ${(capability.send as? Gso.On)?.maxSegments}, gro ${capability.receive})"
            }
        println("[qnet] ${shim.binding} on ${System.getProperty("os.name")} ${System.getProperty("os.version")}: $described")
    }

    @Test
    fun theProbeChoosesTheNewestBatchingTheCeilingAllows() {
        val all = 1 or 2 or 4
        // Linux: every batched call is public.
        assertIs<BatchCapability.Segmented>(NativeSocket.capabilityOf(all, 64, UdpBatching.Newest, 64))
        assertIs<BatchCapability.Multi>(NativeSocket.capabilityOf(all, 64, UdpBatching.WithoutSegments, 64))
        // A socket that coalesces its receives says so; one with no segmented send still receives segmented.
        val coalescing = NativeSocket.capabilityOf(all, 64, UdpBatching.Newest, 64, Gro.On)
        assertEquals(Gro.On, assertIs<BatchCapability.Segmented>(coalescing).receive)
        val receiveOnly = assertIs<BatchCapability.Segmented>(NativeSocket.capabilityOf(1 or 4, 0, UdpBatching.Newest, 64, Gro.On))
        assertIs<Gso.Off>(receiveOnly.send)
        assertIs<BatchCapability.Multi>(NativeSocket.capabilityOf(1, 0, UdpBatching.Newest, 64))
        assertIs<BatchCapability.OneByOne>(NativeSocket.capabilityOf(0, 0, UdpBatching.Newest, 64))
        assertIs<BatchCapability.OneByOne>(NativeSocket.capabilityOf(all, 64, UdpBatching.OneDatagramPerCall, 64))
        // Darwin's batched calls are outside its public SDK: an app-store build opts out of them, and only them.
        val darwin = 1 or 8
        assertIs<BatchCapability.Multi>(NativeSocket.capabilityOf(darwin, 0, UdpBatching.Newest, 64))
        assertIs<BatchCapability.OneByOne>(NativeSocket.capabilityOf(darwin, 0, UdpBatching.PublicCallsOnly, 64))
        assertIs<BatchCapability.Segmented>(NativeSocket.capabilityOf(all, 64, UdpBatching.PublicCallsOnly, 64))
    }

    @Test
    fun aSegmentedSendReachesAGroSocketAsTheDatagramsSentWithTheReceivePathReportingGro() {
        val server = open(V4_LOOPBACK, SocketPeer.Unconnected, UdpBatching.Newest, GRO_SLOTS, GroRequest.Requested)
        val client = open(V4_LOOPBACK, SocketPeer.Connected(server.local), UdpBatching.Newest, GRO_SLOTS)
        if (!System.getProperty("os.name").startsWith("Linux")) {
            // Only Linux and Android coalesce: elsewhere the socket says so and receives one datagram per message.
            assertIs<GroSetup.Unavailable>(server.socket.gro)
            assertEquals(Receiving.PerDatagram, server.socket.receiving)
            return
        }
        assertIs<GroSetup.Enabled>(server.socket.gro)
        assertEquals(Receiving.Coalesced, server.socket.receiving)
        assertEquals(Gro.On, assertIs<BatchCapability.Segmented>(server.io.capability).receive)
        // Two segmented groups of sixteen 1,200-byte datagrams each ending in a short one, then a lone datagram.
        val sizes = List(2) { List(16) { 1200 } + 500 }.flatten() + 1200
        val batch = sendBatch(GRO_SLOTS)
        sizes.forEachIndexed { i, size -> batch.put(i, size, SendTarget.ConnectedPeer) }
        assertEquals(SendBatchResult.AllTaken, client.io.send(batch))
        val got = receiveAll(server.io, sizes.size, GRO_SLOTS)
        assertEquals(sizes, got.map { it.first }, "lengths in order")
        assertEquals(List(sizes.size) { it }, got.map { it.second }, "each datagram's own bytes")
        assertEquals(client.local, got.first().third, "the source is the client")
        assert(widestRead > 1) { "no read held more than one datagram: the kernel did not coalesce" }
    }

    private fun roundTrip(
        ceiling: UdpBatching,
        host: String,
    ) {
        val server = open(host, SocketPeer.Unconnected, ceiling)
        val client = open(host, SocketPeer.Connected(server.local), ceiling)
        val sizes = listOf(1200, 1200, 1200, 1200, 1200, 700, 1200, 1200, 1200)
        val batch = sendBatch()
        sizes.forEachIndexed { i, size -> batch.put(i, size, SendTarget.ConnectedPeer) }
        assertEquals(SendBatchResult.AllTaken, client.io.send(batch), "$ceiling $host")
        val got = receiveAll(server.io, sizes.size)
        assertEquals(sizes, got.map { it.first }, "$ceiling $host: lengths in order")
        assertEquals(List(sizes.size) { it }, got.map { it.second }, "$ceiling $host: each datagram's own bytes")
        assertEquals(client.local, got.first().third, "$ceiling $host: the source is the client")
    }

    private class Opened(
        val io: NativeIo,
        val local: PathKey,
        val socket: NativeSocket,
    )

    private fun open(
        host: String,
        peer: SocketPeer,
        ceiling: UdpBatching,
        slots: Int = BATCH,
        gro: GroRequest = GroRequest.Declined,
    ): Opened {
        assumeNativeStack()
        val socket =
            assertIs<NativeOpened.Opened>(
                NativeSocket.open(
                    shim,
                    native(NativeSocket.SPEC_BYTES),
                    InetSocketAddress(host, 0).toPathKey(),
                    peer,
                    1 shl 20,
                    1 shl 20,
                    ceiling,
                    slots,
                    gro,
                ),
            ).socket
        val io = NativeIo(shim, socket, listOf(native(NativeIo.SEND_RECORD * slots)), native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE))
        opened.add(io)
        return Opened(io, socket.local, socket)
    }

    private fun sendBatch(slots: Int = BATCH): SendBatch = SendBatch(native(slots * DATAGRAM), BatchLimits(slots, DATAGRAM))

    /** Writes datagram [index] as [length] bytes of the value [index], so a torn or misplaced one shows. */
    private fun SendBatch.put(
        index: Int,
        length: Int,
        target: SendTarget,
    ) {
        val at = nextOffset()
        for (b in 0 until length step 8) storage.putLong(at + minOf(b, length - 8), index * 0x0101010101010101L)
        commit(length, target)
    }

    /** (length, the value every byte holds, source) of each datagram, in arrival order. */
    private fun receiveAll(
        io: NativeIo,
        expected: Int,
        slots: Int = BATCH,
    ): List<Triple<Int, Int, PathKey>> {
        widestRead = 0
        val buffers = List(slots) { ReceiveBuffer(it, native(DATAGRAM)) }
        val batch = ReceiveBatch(FixedPool(slots) { buffers[it] }, BatchLimits(slots, DATAGRAM))
        val got = ArrayList<Triple<Int, Int, PathKey>>()
        val until = System.nanoTime() + 5_000_000_000L
        while (got.size < expected && System.nanoTime() < until) {
            when (io.receive(batch)) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    widestRead = maxOf(widestRead, batch.count)
                    for (i in 0 until batch.count) {
                        val bytes = batch.bufferAt(i).bytes
                        val length = batch.lengthAt(i)
                        val first = bytes.byteAt(0)
                        for (b in 0 until length) assertEquals(first, bytes.byteAt(b), "datagram ${got.size} is torn at $b")
                        got.add(Triple(length, first, batch.fromAt(i)))
                    }
                }
                is ReceiveBatchResult.Nothing -> Thread.sleep(1)
                is ReceiveBatchResult.Failed -> Unit
            }
            batch.release()
        }
        return got
    }

    private fun native(size: Int): NativeBytes =
        assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(size))).bytes.also { owned.add(it) }

    private companion object {
        const val BATCH = 16
        const val GRO_SLOTS = 64
        const val DATAGRAM = 1500
        const val V4_LOOPBACK = "127.0.0.1"
        const val V6_LOOPBACK = "::1"
    }
}
