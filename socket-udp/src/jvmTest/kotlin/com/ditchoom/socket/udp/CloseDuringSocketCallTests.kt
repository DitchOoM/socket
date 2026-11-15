package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketOption
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Closing a datagram channel never waits for a receive or a send another thread has in progress.
 *
 * The JDK's `DatagramChannelImpl` closes by taking the lock its `receive` holds, so a close that
 * lands inside one parks the closing thread until the receive returns. [ReceiveHoldsCloseChannel]
 * is that locking with the receive held open by the test, so the overlap is certain instead of a
 * few microseconds wide.
 */
@OptIn(ExperimentalDatagramApi::class)
class CloseDuringSocketCallTests {
    @Test
    fun closeReturnsWhileAReceiveIsInProgressAndTheReceiveClosesTheSocket() {
        val socket = ReceiveHoldsCloseChannel()
        val channel = AddressedNioDatagramChannel(socket, InternedJvmSocketAddress(InetSocketAddress("127.0.0.1", 1)))
        val received = AtomicReference<DatagramReadResult>()
        val receiver = thread(name = "receiver") { received.set(runBlocking { channel.receive() }) }
        assertTrue(socket.receiveEntered.await(WAIT_S, TimeUnit.SECONDS), "the receive never reached the socket")

        val closeReturned = CountDownLatch(1)
        thread(name = "closer") {
            channel.close()
            closeReturned.countDown()
        }
        val returned = closeReturned.await(WAIT_S, TimeUnit.SECONDS)
        val closesDuringReceive = socket.closes.get()

        socket.releaseReceive.countDown()
        receiver.join(WAIT_S * 1000)
        assertTrue(returned, "close() waited for the receive in progress on another thread")
        assertEquals(0, closesDuringReceive, "the socket was closed under the receive")
        assertIs<DatagramReadResult.Closed>(received.get(), "the receive in progress yields Closed")
        assertEquals(1, socket.closes.get(), "the receive closed the socket as it returned")
    }

    @Test
    fun closeWithNoCallInProgressClosesAtOnceAndOnlyOnce() {
        var closes = 0
        val calls = SocketCalls { closes++ }
        assertEquals(7, calls.during { 7 })

        calls.close()
        assertEquals(1, closes)
        calls.close()
        assertEquals(1, closes, "a second close does nothing")
        assertFailsWith<ClosedChannelException> { calls.during { } }
    }

    @Test
    fun theLastCallInProgressCloses() {
        var closes = 0
        val calls = SocketCalls { closes++ }
        calls.begin()
        calls.begin()

        calls.close()
        assertEquals(0, closes, "not while a call is in progress")
        assertFailsWith<ClosedChannelException> { calls.begin() }
        calls.end()
        assertEquals(0, closes, "not while the other call is in progress")
        calls.end()
        assertEquals(1, closes)
    }

    /** A datagram socket whose `receive` holds the lock its close takes, as the JDK's does, until released. */
    private class ReceiveHoldsCloseChannel : DatagramChannel(SelectorProvider.provider()) {
        private val readLock = ReentrantLock()
        val receiveEntered = CountDownLatch(1)
        val releaseReceive = CountDownLatch(1)
        val closes = AtomicInteger()

        override fun receive(dst: ByteBuffer): SocketAddress? {
            readLock.lock()
            try {
                receiveEntered.countDown()
                releaseReceive.await()
                return null
            } finally {
                readLock.unlock()
            }
        }

        override fun implCloseSelectableChannel() {
            readLock.lock()
            readLock.unlock()
            closes.incrementAndGet()
        }

        override fun implConfigureBlocking(block: Boolean) = Unit

        override fun supportedOptions(): Set<SocketOption<*>> = emptySet()

        override fun <T : Any?> getOption(name: SocketOption<T>): T = throw UnsupportedOperationException()

        override fun <T : Any?> setOption(
            name: SocketOption<T>,
            value: T,
        ): DatagramChannel = this

        override fun bind(local: SocketAddress?): DatagramChannel = this

        override fun socket() = throw UnsupportedOperationException()

        override fun isConnected(): Boolean = false

        override fun connect(remote: SocketAddress): DatagramChannel = this

        override fun disconnect(): DatagramChannel = this

        override fun getRemoteAddress(): SocketAddress = throw UnsupportedOperationException()

        override fun getLocalAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 1)

        override fun send(
            src: ByteBuffer,
            target: SocketAddress,
        ): Int = 0

        override fun read(dst: ByteBuffer): Int = 0

        override fun read(
            dsts: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = 0

        override fun write(src: ByteBuffer): Int = 0

        override fun write(
            srcs: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = 0

        override fun join(
            group: java.net.InetAddress,
            interf: NetworkInterface,
        ): MembershipKey = throw UnsupportedOperationException()

        override fun join(
            group: java.net.InetAddress,
            interf: NetworkInterface,
            source: java.net.InetAddress,
        ): MembershipKey = throw UnsupportedOperationException()
    }

    private companion object {
        /** Only a failing run waits this long. */
        const val WAIT_S = 5L
    }
}
