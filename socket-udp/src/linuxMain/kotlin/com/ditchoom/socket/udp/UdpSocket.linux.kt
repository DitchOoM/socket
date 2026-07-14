@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.linux.socket_bind
import com.ditchoom.socket.udp.linux.socket_connect
import com.ditchoom.socket.udp.linux.socket_getsockname
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.close
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * Linux/K-N [UdpSocket] over io_uring (see [IoUringDatagramChannel]). Sockets match the family of the
 * bind/connect address (an IPv4 literal → `AF_INET`, matching the JVM actual's family-follows-address
 * behavior), so the conformance suite's `127.0.0.1` binds report an IPv4 [SocketAddress.localAddress].
 */
@ExperimentalDatagramApi
actual object UdpSocket {
    init {
        // Referencing UdpSocket wires SocketAddress.resolve() to real DNS process-wide (RFC §10.1).
        SocketAddress.installResolver(LinuxSocketAddressResolver)
    }

    actual suspend fun bind(
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
    ): DatagramChannel {
        val local = LinuxSocketAddressResolver.resolve(localHost ?: WILDCARD_V4, localPort) as LinuxSocketAddress
        val fd = openDatagramSocket(local.family)
        setReuseAddr(fd)
        bindTo(fd, local)
        return IoUringDatagramChannel(
            fd = fd,
            connected = false,
            connectedPeer = null,
            localAddress = localAddressOf(fd),
            ipv6 = local.family == AddressFamily.IPv6,
            receiveBufferSize = receiveBufferSize,
        ).also { IoUringManager.onSocketOpened() }
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
    ): DatagramChannel {
        val peer = resolve(remoteHost, remotePort) as LinuxSocketAddress
        val fd = openDatagramSocket(peer.family)
        setReuseAddr(fd)
        // Bind the local endpoint only when one was named (else the kernel auto-binds on connect).
        if (localHost != null || localPort != 0) {
            val wildcard = if (peer.family == AddressFamily.IPv6) WILDCARD_V6 else WILDCARD_V4
            val local = LinuxSocketAddressResolver.resolve(localHost ?: wildcard, localPort) as LinuxSocketAddress
            bindTo(fd, local)
        }
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val len = peer.writeSockaddr(addr)
            if (socket_connect(fd, addr.ptr.reinterpret(), len) != 0) {
                close(fd)
                throw IllegalStateException("connect to ${peer.host}:${peer.port} failed")
            }
        }
        return IoUringDatagramChannel(
            fd = fd,
            connected = true,
            connectedPeer = peer,
            localAddress = localAddressOf(fd),
            ipv6 = peer.family == AddressFamily.IPv6,
            receiveBufferSize = receiveBufferSize,
        ).also { IoUringManager.onSocketOpened() }
    }

    actual suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress = SocketAddress.resolve(host, port)

    private fun openDatagramSocket(family: AddressFamily): Int {
        val af = if (family == AddressFamily.IPv6) AF_INET6 else AF_INET
        val fd = socket(af, SOCK_DGRAM, IPPROTO_UDP)
        check(fd >= 0) { "socket(AF=$af, SOCK_DGRAM) failed" }
        return fd
    }

    private fun setReuseAddr(fd: Int) {
        memScoped {
            val v = alloc<IntVar>()
            v.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, v.ptr, sizeOf<IntVar>().convert())
        }
    }

    private fun bindTo(
        fd: Int,
        local: LinuxSocketAddress,
    ) {
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val len = local.writeSockaddr(addr)
            if (socket_bind(fd, addr.ptr.reinterpret(), len) != 0) {
                close(fd)
                error("bind to ${local.host}:${local.port} failed")
            }
        }
    }

    private fun localAddressOf(fd: Int): SocketAddress? =
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_storage>().convert()
            if (socket_getsockname(fd, addr.ptr.reinterpret(), len.ptr) != 0) {
                return@memScoped null
            }
            sockaddrToLinuxSocketAddress(addr.ptr.reinterpret<sockaddr>())
        }

    private const val WILDCARD_V4 = "0.0.0.0"
    private const val WILDCARD_V6 = "::"
}
