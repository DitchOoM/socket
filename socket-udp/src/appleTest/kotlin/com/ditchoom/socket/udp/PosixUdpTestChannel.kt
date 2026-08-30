@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.F_GETFD
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A [PosixUdpDatagramChannel] under test together with the descriptor number it was built over. */
@OptIn(ExperimentalDatagramApi::class)
internal class BoundPosixChannel(
    val fd: Int,
    val channel: PosixUdpDatagramChannel,
)

/**
 * A bound `127.0.0.1:0` datagram socket wrapped directly in the channel under test — the same
 * construction `UdpSocket.bind` performs, minus the options it sets, so the `beforeDispatch` seam can
 * be injected and the descriptor number is known to the test.
 */
@OptIn(ExperimentalDatagramApi::class)
internal suspend fun boundLoopbackPosixChannel(beforeDispatch: suspend () -> Unit = {}): BoundPosixChannel {
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
        BoundPosixChannel(fd, PosixUdpDatagramChannel(fd, bound, beforeDispatch = beforeDispatch))
    }
}

/** Whether [fd] currently names an open descriptor in this process (`fcntl(F_GETFD)` is EBADF otherwise). */
internal fun isOpenDescriptor(fd: Int): Boolean = fcntl(fd, F_GETFD) != -1

/** `MultiWorkerDispatcher.dispatch` refuses work once closed; that refusal is the only public probe. */
@OptIn(ExperimentalDatagramApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun assertRecvDispatcherIsClosed(channel: PosixUdpDatagramChannel) {
    val refused =
        assertFailsWith<IllegalStateException>("the receive dispatcher must be closed once the channel is") {
            withContext(channel.recvDispatcher) { }
        }
    assertTrue(refused.message.orEmpty().contains("was closed"), "unexpected refusal: ${refused.message}")
}
