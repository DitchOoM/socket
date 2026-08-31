@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.udp.linux.socket_bind
import com.ditchoom.socket.udp.linux.socket_getsockname
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
import platform.posix.F_GETFD
import platform.posix.IPPROTO_UDP
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.fcntl
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
 * `close()` never closes a descriptor an io_uring submission can still name (#526).
 *
 * This is Apple's #507 with a wider window. `IoUringDatagramChannelCore.receive()` read a closed flag
 * and then called `IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_recvmsg(sqe, fd, …) }` —
 * and that lambda does **not** run at the call site. It rides a `Channel` to the process-global poller
 * thread, which invokes it in its drain loop. So the descriptor number was read after a channel
 * hand-off *and* a poller iteration, while `close()` was flag → `close(fd)` → `onSocketClosed()`. Any
 * `socket()`/`open()`/`accept()` in the process that took the freed number in between made the
 * submission read another socket's datagram — or, on the send path, write into it.
 *
 * **The obvious assertion cannot see this.** A receiver that steals a datagram still reports
 * `DatagramReadResult.Closed`, because the post-completion closed check discards whatever it read.
 * The theft is only observable from the **victim**, which is quietly missing a datagram it was sent.
 * So the primary claim below is not about the receiver at all: it is that every fresh socket still
 * holds its own datagram. (#507 recorded the same lesson; this test is its Linux twin.)
 *
 * The witness parks a receiver in exactly the window — the `beforeSubmit` seam, which sits inside the
 * admission and before the submission is handed off — runs `close()`, then opens fresh datagram
 * sockets. `socket()` hands out the lowest free descriptor number, so if the channel's fd has been
 * closed the first fresh socket takes its number; each fresh socket sends itself one datagram.
 * Released, the receiver must report `Closed`, every fresh socket must still hold its own datagram,
 * and the channel's fd must have been open the whole time the receiver was parked and released only
 * once it left.
 */
@OptIn(ExperimentalDatagramApi::class)
class IoUringCloseNeverUnderAReaderTests {
    @Test
    fun receiverAdmittedBeforeClose_neverNamesAReusedDescriptorNumber() =
        runBlocking {
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val bound =
                boundLoopbackIoUringChannel(
                    beforeSubmit = {
                        parked.complete(Unit)
                        release.await()
                    },
                )
            val channel = bound.channel
            val fresh = ArrayList<FreshSocket>()
            try {
                val receiver = async(Dispatchers.Default) { channel.receive() }
                withTimeout(WAIT) { parked.await() }
                // The receiver is admitted and its submission has not reached the poller. Everything
                // close() does happens here.
                channel.close()
                assertFalse(channel.isOpen)
                // Recorded now, asserted after the primary claim, so a failure names the theft first.
                val fdOpenWhileParked = isOpenDescriptor(bound.fd)
                repeat(FRESH_SOCKETS) { fresh += FreshSocket.openWithOwnDatagram("fresh-$it") }
                val freshFds = fresh.map { it.fd }
                release.complete(Unit)
                val result = withTimeout(WAIT) { receiver.await() }

                // The primary claim. Unfixed, the parked submission's io_uring_prep_recvmsg named the
                // recycled number and the first fresh socket's datagram was consumed by the poller.
                for (socket in fresh) {
                    assertEquals(
                        socket.payload,
                        socket.readOwnDatagram(),
                        "fresh socket fd=${socket.fd} lost its datagram; channel fd=${bound.fd} " +
                            "(channel's number reused: ${bound.fd in freshFds}), receiver reported ${describe(result)}",
                    )
                }
                assertIs<DatagramReadResult.Closed>(
                    result,
                    "a receiver admitted before close() is owed Closed; channel fd=${bound.fd}, fresh fds=$freshFds " +
                        "(channel's number reused: ${bound.fd in freshFds}); got ${describe(result)}",
                )
                assertTrue(fdOpenWhileParked, "close() must not close fd ${bound.fd} while a receiver is admitted")
                assertFalse(isOpenDescriptor(bound.fd), "the receiver, last out, must release fd ${bound.fd}")
            } finally {
                fresh.forEach { it.close() }
                channel.close()
            }
        }

    /**
     * A receiver that arrives *after* `close()` is refused without naming the descriptor at all, and the
     * refusal does not disturb who owns the release.
     *
     * The complement of the test above: there the admission is what keeps the descriptor alive, here it
     * is what stops a late caller from touching a number the process may already have handed out again.
     */
    @Test
    fun receiverArrivingAfterCloseIsRefusedWithoutASyscall() =
        runBlocking {
            val bound = boundLoopbackIoUringChannel()
            val channel = bound.channel
            channel.close()
            assertFalse(isOpenDescriptor(bound.fd), "an uncontended close() releases the descriptor itself")

            val fresh = ArrayList<FreshSocket>()
            try {
                repeat(FRESH_SOCKETS) { fresh += FreshSocket.openWithOwnDatagram("late-$it") }
                assertIs<DatagramReadResult.Closed>(withTimeout(WAIT) { channel.receive() })
                for (socket in fresh) {
                    assertEquals(
                        socket.payload,
                        socket.readOwnDatagram(),
                        "fresh socket fd=${socket.fd} lost its datagram to a receive() on a closed channel",
                    )
                }
            } finally {
                fresh.forEach { it.close() }
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
                if (poll(fds.ptr, 1.convert(), READ_TIMEOUT_MILLIS) <= 0) {
                    return "<no datagram within ${READ_TIMEOUT_MILLIS}ms>"
                }
                val landing = allocArray<ByteVar>(LANDING_SIZE)
                val n = recvfrom(fd, landing, LANDING_SIZE.convert(), 0, null, null).toInt()
                if (n < 0) return "<recvfrom failed>"
                landing.readBytes(n).decodeToString()
            }

        fun close() {
            close(fd)
        }

        companion object {
            fun openWithOwnDatagram(payload: String): FreshSocket {
                val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                check(fd >= 0) { "socket(AF_INET, SOCK_DGRAM) failed" }
                memScoped {
                    val storage = alloc<sockaddr_storage>()
                    val length = loopbackV4.writeSockaddr(storage)
                    check(socket_bind(fd, storage.ptr.reinterpret(), length) == 0) { "bind 127.0.0.1:0 failed" }
                    val boundLength = alloc<UIntVar>()
                    boundLength.value = sizeOf<sockaddr_storage>().convert()
                    check(socket_getsockname(fd, storage.ptr.reinterpret(), boundLength.ptr) == 0) {
                        "getsockname failed"
                    }
                    val sent =
                        sendto(
                            fd,
                            payload.cstr.ptr,
                            payload.length.convert(),
                            0,
                            storage.ptr.reinterpret(),
                            boundLength.value,
                        )
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

        /** Whether [fd] names an open descriptor in this process. */
        fun isOpenDescriptor(fd: Int): Boolean = fcntl(fd, F_GETFD) != -1
    }
}
