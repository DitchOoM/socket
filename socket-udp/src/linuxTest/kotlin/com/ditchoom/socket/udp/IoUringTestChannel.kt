@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.udp.linux.socket_bind
import com.ditchoom.socket.udp.linux.socket_getsockname
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket

/** The loopback address, built without DNS so a test never depends on the resolver. */
internal val loopbackV4 = LinuxSocketAddress("127.0.0.1", 0, AddressFamily.IPv4, hi = 0L, lo = 0x7F000001L)

/**
 * An [AddressedIoUringDatagramChannel] on a socket **this test created**, so the test knows the
 * descriptor number.
 *
 * `UdpSocket.bind` opens the socket itself and keeps the number private, which is exactly right for
 * production and useless for a test about descriptor reuse: the claim in
 * [IoUringCloseNeverUnderAReaderTests] is that a *specific number* is not named after it is freed, and
 * it cannot be made without knowing which number that is.
 */
@ExperimentalDatagramApi
internal class BoundIoUringChannel(
    val fd: Int,
    val channel: AddressedIoUringDatagramChannel,
)

/**
 * Bind a fresh IPv4 loopback datagram socket and wrap it exactly as `UdpSocket.bind` does — same
 * channel class, same [IoUringManager.onSocketOpened] accounting — plus the [beforeSubmit] seam.
 */
@ExperimentalDatagramApi
internal fun boundLoopbackIoUringChannel(beforeSubmit: suspend () -> Unit = {}): BoundIoUringChannel {
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    check(fd >= 0) { "socket(AF_INET, SOCK_DGRAM) failed" }
    val boundLocal =
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val length = loopbackV4.writeSockaddr(storage)
            if (socket_bind(fd, storage.ptr.reinterpret(), length) != 0) {
                close(fd)
                error("bind 127.0.0.1:0 failed")
            }
            val readBack = alloc<UIntVar>()
            readBack.value = sizeOf<sockaddr_storage>().convert()
            if (socket_getsockname(fd, storage.ptr.reinterpret(), readBack.ptr) != 0) {
                close(fd)
                error("getsockname failed")
            }
            sockaddrToLinuxSocketAddress(storage.ptr.reinterpret<sockaddr>()) ?: run {
                close(fd)
                error("bound socket reported an address this backend cannot decode")
            }
        }
    val channel =
        AddressedIoUringDatagramChannel(
            fd = fd,
            localAddress = boundLocal,
            ipv6 = false,
            beforeSubmit = beforeSubmit,
        )
    IoUringManager.onSocketOpened()
    return BoundIoUringChannel(fd, channel)
}
