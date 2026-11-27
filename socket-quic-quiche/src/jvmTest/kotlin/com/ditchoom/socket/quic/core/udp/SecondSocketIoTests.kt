package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.network
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A client io's second socket on the real kernel, on both stacks: opening one while one is open closes the earlier one,
 * a close closes both, and a promotion never sends a datagram tagged for the old socket out of the new one.
 */
class SecondSocketIoTests {
    private val shim by lazy { loadNetShim() }
    private val owned = ArrayList<NativeBytes>()
    private val channels = ArrayList<DatagramChannel>()

    @AfterTest
    fun freeAll() {
        channels.forEach { it.close() }
        owned.forEach { it.free() }
    }

    @Test
    fun aSecondChannelOpenedWhileOneIsOpenClosesTheEarlierOne() {
        val io = jdkClient(server().localAddress as InetSocketAddress)
        assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(1)))
        val earlier = io.secondChannel
        assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(2)))
        assertTrue(!earlier.isOpen, "the earlier second channel was closed")
        assertTrue(io.secondChannel !== earlier && io.secondChannel.isOpen)
        io.close()
    }

    @Test
    fun aJdkCloseClosesTheSecondChannelToo() {
        val io = jdkClient(server().localAddress as InetSocketAddress)
        assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(1)))
        val second = io.secondChannel
        io.close()
        assertTrue(!second.isOpen, "the second channel was closed with the io")
    }

    @Test
    fun aSecondDescriptorOpenedWhileOneIsOpenClosesTheEarlierOne() {
        val io = nativeClient(server().localAddress as InetSocketAddress)
        val earlier = assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(1))).local
        val later = assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(2))).local
        // The earlier socket's port says whether it was closed: a descriptor number can be reused by any thread in the
        // process at once, a port held by an open socket refuses another bind. The later socket may itself get the port.
        if (later.port != earlier.port) {
            DatagramChannel.open().use { probe -> probe.bind(InetSocketAddress("127.0.0.1", earlier.port)) }
        }
        io.close()
    }

    @Test
    fun aNativeCloseClosesTheSecondDescriptorToo() {
        val io = nativeClient(server().localAddress as InetSocketAddress)
        val second = assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(1))).local
        io.close()
        // The port says the socket was closed with the io; its descriptor number may already be another socket's.
        DatagramChannel.open().use { probe -> probe.bind(InetSocketAddress("127.0.0.1", second.port)) }
    }

    /**
     * A lane sends datagrams tagged for the carrying socket while the loop promotes the second, round after round: none
     * of them leaves the promoted socket, whose address and connection id they do not carry.
     */
    @Test
    fun aPromotionSendsNothingTaggedForTheOldSocketOutOfTheNewOne() {
        val server = server()
        val peer = server.localAddress as InetSocketAddress
        var misrouted = 0
        repeat(ROUNDS) { round ->
            val io = nativeClient(peer)
            val opened = assertIs<Beside.Opened>(io.openBeside(LOOPBACK_ANY, SocketTicket(1)))
            val sending = AtomicBoolean(true)
            val sent = AtomicInteger(0)
            val batch = SendBatch(native(DATAGRAM), BatchLimits(1, DATAGRAM))
            val lane =
                thread {
                    while (sending.get()) {
                        batch.storage.putInt(0, round)
                        batch.commit(DATAGRAM, SendTarget.ConnectedPeer, SocketTicket.FIRST)
                        io.send(batch)
                        batch.took(batch.pending)
                        sent.incrementAndGet()
                    }
                }
            while (sent.get() < WARM_SENDS) Thread.onSpinWait()
            io.promote()
            sending.set(false)
            lane.join()
            misrouted += drain(server, round, opened.local.port)
            io.close()
        }
        assertEquals(0, misrouted, "datagrams tagged for the old socket left the promoted one")
    }

    /** Reads what [server] holds; counts this [round]'s datagrams that came from [port]. */
    private fun drain(
        server: DatagramChannel,
        round: Int,
        port: Int,
    ): Int {
        val into = ByteBuffer.allocate(DATAGRAM)
        var counted = 0
        while (true) {
            into.clear()
            val from = server.receive(into) as? InetSocketAddress ?: return counted
            if (into.position() >= 4 && into.getInt(0) == round && from.port == port) counted++
        }
    }

    private fun server(): DatagramChannel =
        DatagramChannel.open().apply {
            bind(InetSocketAddress("127.0.0.1", 0))
            configureBlocking(false)
            channels.add(this)
        }

    private fun jdkClient(peer: InetSocketAddress): NioOneByOne {
        val channel =
            DatagramChannel.open().apply {
                bind(InetSocketAddress("127.0.0.1", 0))
                connect(peer)
                configureBlocking(false)
            }
        return NioOneByOne(channel, emptyList(), emptyList())
    }

    private fun nativeClient(peer: InetSocketAddress): NativeIo {
        assumeNativeStack()
        val socket =
            assertIs<NativeOpened.Opened>(
                NativeSocket.open(
                    shim,
                    native(NativeSocket.SPEC_BYTES),
                    InetSocketAddress("127.0.0.1", 0).toPathKey(),
                    SocketPeer.Connected(peer.toPathKey()),
                    1 shl 20,
                    1 shl 20,
                    UdpBatching.Newest,
                    1,
                ),
            ).socket
        return NativeIo(shim, socket, listOf(native(NativeIo.SEND_RECORD)), native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE))
    }

    private fun native(size: Int): NativeBytes =
        assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(size))).bytes.also { owned.add(it) }

    private companion object {
        const val DATAGRAM = 64
        const val ROUNDS = 400
        const val WARM_SENDS = 20
        val LOOPBACK_ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0x7F000001L)
    }
}
