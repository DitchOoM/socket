@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `close()` never closes a descriptor a reader can still reach (#507).
 *
 * After #506, `close()` and a concurrent `receive()` agreed on who closes the dispatcher — but
 * `close()` still closed the fd first, because closing it was what unblocked an in-flight `recvfrom`.
 * A receiver admitted just before `close()` and not yet inside the syscall then called `recvfrom` on a
 * descriptor *number* that was already closed: `EBADF` while the number is free, and — once any
 * `socket()`/`open()`/`accept()` in the process has reused it — another socket's datagram, delivered
 * as a valid-looking [DatagramReadResult.Received].
 *
 * The witness holds a receiver in exactly that window (the channel's `beforeDispatch` seam), runs
 * `close()`, and then opens fresh datagram sockets. `socket()` hands out the lowest free descriptor
 * number, so if the channel's fd has been closed the first fresh socket takes its number; each fresh
 * socket sends itself one datagram. Released, the receiver must report `Closed`, every fresh socket
 * must still hold its own datagram, and the channel's fd must have been open while the receiver was
 * parked and released only once it left. Unfixed, the receiver reads the first fresh socket's datagram
 * and reports it as `Received`.
 */
@OptIn(ExperimentalDatagramApi::class)
class PosixUdpCloseNeverUnderAReaderTests {
    @Test
    fun receiverAdmittedBeforeClose_neverReadsAReusedDescriptorNumber() =
        runBlocking {
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val bound =
                boundLoopbackPosixChannel(
                    beforeDispatch = {
                        parked.complete(Unit)
                        release.await()
                    },
                )
            val channel = bound.channel
            val fresh = ArrayList<FreshSocket>()
            try {
                val receiver = async(Dispatchers.Default) { channel.receive() }
                withTimeout(WAIT) { parked.await() }
                // The receiver is admitted and has not yet reached the syscall. Everything close() does
                // happens here.
                channel.close()
                assertFalse(channel.isOpen)
                // Recorded now, asserted after the primary claim, so the failure names the theft.
                val fdOpenWhileParked = isOpenDescriptor(bound.fd)
                repeat(FRESH_SOCKETS) { fresh += FreshSocket.openWithOwnDatagram("fresh-$it") }
                val freshFds = fresh.map { it.fd }
                release.complete(Unit)
                val result = withTimeout(WAIT) { receiver.await() }
                assertIs<DatagramReadResult.Closed>(
                    result,
                    "a receiver admitted before close() is owed Closed; channel fd=${bound.fd}, fresh fds=$freshFds " +
                        "(channel's number reused: ${bound.fd in freshFds}); got ${describe(result)}",
                )
                // Unfixed, the receiver's recvfrom(fd) consumed the first fresh socket's datagram and the
                // closed check then discarded it as Closed — so the theft is only visible from here.
                for (socket in fresh) {
                    assertEquals(
                        socket.payload,
                        socket.readOwnDatagram(),
                        "fresh socket fd=${socket.fd} lost its datagram; channel fd=${bound.fd} " +
                            "(channel's number reused: ${bound.fd in freshFds}), receiver reported ${describe(result)}",
                    )
                }
                assertTrue(fdOpenWhileParked, "close() must not close fd ${bound.fd} while a receiver is admitted")
                assertFalse(isOpenDescriptor(bound.fd), "the receiver, last out, must release fd ${bound.fd}")
                assertRecvDispatcherIsClosed(channel)
            } finally {
                fresh.forEach { it.close() }
                channel.close()
            }
        }

    private fun describe(result: DatagramReadResult): String =
        when (result) {
            is DatagramReadResult.Received -> {
                val payload = result.datagram.payload
                "Received(${payload.readString(payload.remaining())} from ${result.datagram.peer})"
            }
            is DatagramReadResult.Closed -> "Closed(${result.reason})"
        }

    /** A bound loopback datagram socket that has sent itself exactly one datagram carrying [payload]. */
    private class FreshSocket(
        val fd: Int,
        val payload: String,
    ) {
        /** The datagram this socket holds, or a description of why it holds none. */
        fun readOwnDatagram(): String =
            memScoped {
                val fds = alloc<pollfd>()
                fds.fd = fd
                fds.events = POLLIN.convert()
                fds.revents = 0
                if (poll(fds.ptr, 1.convert(), READ_TIMEOUT_MILLIS) <= 0) return "<no datagram within ${READ_TIMEOUT_MILLIS}ms>"
                val landing = allocArray<ByteVar>(LANDING_SIZE)
                val n = recvfrom(fd, landing, LANDING_SIZE.convert(), 0, null, null).toInt()
                if (n < 0) return "<recvfrom failed>"
                landing.readBytes(n).decodeToString()
            }

        fun close() {
            close(fd)
        }

        companion object {
            suspend fun openWithOwnDatagram(payload: String): FreshSocket {
                val local = AppleSocketAddressResolver.resolve("127.0.0.1", 0)
                val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                check(fd >= 0) { "socket(AF_INET, SOCK_DGRAM) failed" }
                memScoped {
                    val storage = alloc<sockaddr_storage>()
                    val length = local.writeSockaddr(storage)
                    check(bind(fd, storage.ptr.reinterpret(), length) == 0) { "bind 127.0.0.1:0 failed" }
                    val boundLength = alloc<UIntVar>()
                    boundLength.value = sizeOf<sockaddr_storage>().convert()
                    check(getsockname(fd, storage.ptr.reinterpret(), boundLength.ptr) == 0) { "getsockname failed" }
                    val sent =
                        sendto(fd, payload.cstr.ptr, payload.length.convert(), 0, storage.ptr.reinterpret(), boundLength.value)
                    check(sent.toInt() == payload.length) { "self-send of '$payload' on fd $fd failed" }
                }
                return FreshSocket(fd, payload)
            }
        }
    }

    private companion object {
        val WAIT = 10.seconds
        const val FRESH_SOCKETS = 4
        const val READ_TIMEOUT_MILLIS = 2_000
        const val LANDING_SIZE = 64
    }
}
