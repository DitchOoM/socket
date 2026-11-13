@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.POLLIN
import platform.posix.poll
import platform.posix.pollfd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A send to a socket with room, and a receive from a socket that already holds a datagram, finish on the
 * caller's thread: neither suspends, so neither costs a hand-off to the ring's thread and back. Only a socket
 * with no room, or no datagram, sends its caller to the ring to wait.
 *
 * "Did not suspend" is observed with an undispatched coroutine: it runs on the test's thread until its first
 * suspension, so one that is complete as soon as `async` returns never suspended.
 */
@OptIn(ExperimentalDatagramApi::class)
class IoUringDatagramWithoutWaitingTests {
    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private fun DatagramReadResult.text(): String {
        val datagram = assertIs<DatagramReadResult.Received>(this).datagram
        return datagram.payload.readString(datagram.payload.remaining())
    }

    @Test
    fun aSendToASocketWithRoomDoesNotSuspend() =
        runBlocking {
            withTimeout(30_000) {
                val sender = boundLoopbackIoUringChannel()
                val receiver = boundLoopbackIoUringChannel()
                try {
                    val sent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            sender.channel.send(payload("now"), receiver.channel.localAddress, DatagramSendOptions())
                        }
                    assertTrue(sent.isCompleted, "the send suspended although the socket had room")
                    sent.await()
                    assertEquals("now", receiver.channel.receive().text())
                } finally {
                    sender.channel.close()
                    receiver.channel.close()
                }
            }
        }

    @Test
    fun aReceiveOfAQueuedDatagramDoesNotSuspend() =
        runBlocking {
            withTimeout(30_000) {
                val sender = boundLoopbackIoUringChannel()
                val receiver = boundLoopbackIoUringChannel()
                try {
                    sender.channel.send(payload("queued"), receiver.channel.localAddress, DatagramSendOptions())
                    awaitReadable(receiver.fd)
                    val received = async(start = CoroutineStart.UNDISPATCHED) { receiver.channel.receive() }
                    assertTrue(received.isCompleted, "the receive suspended although a datagram was queued")
                    assertEquals("queued", received.await().text())
                } finally {
                    sender.channel.close()
                    receiver.channel.close()
                }
            }
        }

    @Test
    fun aReceiveFromAnEmptySocketWaitsForTheDatagram() =
        runBlocking {
            withTimeout(30_000) {
                val sender = boundLoopbackIoUringChannel()
                val receiver = boundLoopbackIoUringChannel()
                try {
                    val received = async(start = CoroutineStart.UNDISPATCHED) { receiver.channel.receive() }
                    assertFalse(received.isCompleted, "a receive from an empty socket returned without a datagram")
                    delay(50)
                    sender.channel.send(payload("later"), receiver.channel.localAddress, DatagramSendOptions())
                    assertEquals("later", received.await().text())
                } finally {
                    sender.channel.close()
                    receiver.channel.close()
                }
            }
        }

    /**
     * More than two bursts, so the receives that wait once between bursts are covered with datagrams queued.
     * Each datagram arrives exactly once; their order is not asserted, because UDP promises none (loopback
     * queues per CPU, and a sender that moves between two can be overtaken).
     */
    @Test
    fun aBacklogLongerThanABurstIsDeliveredWhole() =
        runBlocking {
            withTimeout(30_000) {
                val sender = boundLoopbackIoUringChannel()
                val receiver = boundLoopbackIoUringChannel()
                try {
                    val expected = List(BACKLOG) { "datagram-$it" }
                    expected.forEach { sender.channel.send(payload(it), receiver.channel.localAddress, DatagramSendOptions()) }
                    val received = List(BACKLOG) { receiver.channel.receive().text() }
                    assertEquals(expected.sorted(), received.sorted())
                } finally {
                    sender.channel.close()
                    receiver.channel.close()
                }
            }
        }

    @Test
    fun aClosedChannelReportsClosedRatherThanTheDatagramItStillHolds() =
        runBlocking {
            withTimeout(30_000) {
                val sender = boundLoopbackIoUringChannel()
                val receiver = boundLoopbackIoUringChannel()
                try {
                    sender.channel.send(payload("unread"), receiver.channel.localAddress, DatagramSendOptions())
                    receiver.channel.close()
                    val afterClose = receiver.channel.receive()
                    assertTrue(afterClose is DatagramReadResult.Closed, "a closed channel delivered $afterClose")
                } finally {
                    sender.channel.close()
                    receiver.channel.close()
                }
            }
        }

    /** Loopback delivery can trail the `sendmsg` that caused it, so "queued" is asked of the kernel, not assumed. */
    private fun awaitReadable(fd: Int) =
        memScoped {
            val watched = alloc<pollfd>()
            watched.fd = fd
            watched.events = POLLIN.convert()
            assertEquals(1, poll(watched.ptr, 1u, 5_000), "no datagram became readable")
        }

    private companion object {
        const val BACKLOG = 150
    }
}
