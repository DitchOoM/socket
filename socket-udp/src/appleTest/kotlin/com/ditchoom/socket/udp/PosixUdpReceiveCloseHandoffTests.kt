@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Who closes `PosixUdpDatagramChannel`'s receive dispatcher, and when (#498).
 *
 * `receive()` was a check-then-dispatch — read the closed flag, then `withContext(recvDispatcher)` —
 * while `close()` closed that dispatcher right after closing the fd. A receiver that had passed the
 * check but not yet dispatched met a closed single-thread dispatcher, and kotlinx reported it as
 * `IllegalStateException: Dispatcher apple-udp-recv-N was closed, attempted to schedule` — a kotlinx
 * internal where the caller was owed [DatagramReadResult.Closed]. Sighted in
 * `AppleQuicServerTests.sharedPort_quicAndANonQuicProtocolCoexist[iosSimulatorArm64]`.
 *
 * The window is a few instructions wide, so a timed race would not reproduce it (the #471 harness
 * lessons). The channel's `beforeDispatch` seam runs in exactly that window, so the witness holds a
 * receiver there structurally: park it after the check, run the whole of `close()`, release it, and
 * look at what `receive()` returns. Unfixed, that is the exception above on every run; fixed, it is
 * `Closed`, and the dispatcher is closed afterwards all the same — by the receiver, which was the last
 * one out.
 */
@OptIn(ExperimentalDatagramApi::class)
class PosixUdpReceiveCloseHandoffTests {
    @Test
    fun receiverParkedBetweenTheClosedCheckAndTheDispatch_seesClosedNotADeadDispatcher() =
        runBlocking {
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val channel =
                boundLoopbackChannel(
                    beforeDispatch = {
                        parked.complete(Unit)
                        release.await()
                    },
                )
            try {
                val receiver = async(Dispatchers.Default) { channel.receive() }
                withTimeout(WAIT) { parked.await() }
                // The receiver now sits past the closed check and before its hop onto the dispatcher.
                // Everything close() does — flag, fd, and (unfixed) the dispatcher — happens here.
                channel.close()
                assertFalse(channel.isOpen)
                release.complete(Unit)
                val result = withTimeout(WAIT) { receiver.await() }
                assertIs<DatagramReadResult.Closed>(result, "a receiver that raced close() is owed Closed")
                // And the dispatcher did not outlive the channel: the receiver, last out, closed it.
                assertRecvDispatcherIsClosed(channel)
            } finally {
                channel.close()
            }
        }

    @Test
    fun closeWithNoReceiverInFlight_closesTheDispatcherItself() =
        runBlocking {
            val channel = boundLoopbackChannel()
            // Live before: the probe below is only evidence if it can succeed on an open channel.
            withContext(channel.recvDispatcher) { }
            channel.close()
            assertFalse(channel.isOpen)
            assertRecvDispatcherIsClosed(channel)
            // Idempotent, and a later receive is the typed end, not a dispatch onto a dead dispatcher.
            channel.close()
            assertIs<DatagramReadResult.Closed>(channel.receive())
            Unit
        }

    /** `MultiWorkerDispatcher.dispatch` refuses work once closed; that refusal is the only public probe. */
    private suspend fun assertRecvDispatcherIsClosed(channel: PosixUdpDatagramChannel) {
        val refused =
            assertFailsWith<IllegalStateException>("the receive dispatcher must be closed once the channel is") {
                withContext(channel.recvDispatcher) { }
            }
        assertTrue(refused.message.orEmpty().contains("was closed"), "unexpected refusal: ${refused.message}")
    }

    /**
     * A bound `127.0.0.1:0` datagram socket wrapped directly in the channel under test — the same
     * construction `UdpSocket.bind` performs, minus the options it sets, so the seam can be injected.
     */
    private suspend fun boundLoopbackChannel(beforeDispatch: suspend () -> Unit = {}): PosixUdpDatagramChannel {
        val local = AppleSocketAddressResolver.resolve("127.0.0.1", 0)
        return memScoped {
            val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            check(fd >= 0) { "socket(AF_INET, SOCK_DGRAM) failed" }
            val storage = alloc<sockaddr_storage>()
            val length = local.writeSockaddr(storage)
            if (bind(fd, storage.ptr.reinterpret(), length) != 0) {
                close(fd)
                error("bind 127.0.0.1:0 failed")
            }
            val boundLength = alloc<UIntVar>()
            boundLength.value = sizeOf<sockaddr_storage>().convert()
            if (getsockname(fd, storage.ptr.reinterpret(), boundLength.ptr) != 0) {
                close(fd)
                error("getsockname failed")
            }
            val bound =
                sockaddrToAppleSocketAddress(storage.ptr.reinterpret<sockaddr>()) ?: run {
                    close(fd)
                    error("bound address is not routable")
                }
            PosixUdpDatagramChannel(fd, bound, beforeDispatch = beforeDispatch)
        }
    }

    private companion object {
        val WAIT = 10.seconds
    }
}
