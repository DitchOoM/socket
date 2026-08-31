@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.LocalAddress
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
import platform.posix.SO_REUSEPORT
import platform.posix.close
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * The Linux/K-N default: a native deterministic factory (`malloc`/`free` `NativeBuffer`). io_uring
 * `recvmsg` writes into the payload's raw native memory, so — unlike the JVM — `BufferFactory.Default`
 * (a GC `ByteArrayBuffer` with no native address) is *not* usable here; this is the exact strategy
 * [IoUringDatagramChannelCore] has always used (formerly `PlatformBuffer.allocateNative`).
 */
internal actual val defaultDatagramBufferFactory: BufferFactory = BufferFactory.deterministic()

/**
 * Linux/K-N [UdpSocket] over io_uring (see [IoUringDatagramChannelCore]). Sockets match the family of
 * the bind/connect address (an IPv4 literal → `AF_INET`, matching the JVM actual's family-follows-address
 * behavior), so the conformance suite's `127.0.0.1` binds report an IPv4 local address. The addressing
 * mode is fixed here: [bind]/[bindMulticast] construct the addressed channel (non-null `localAddress`,
 * failing fast on getsockname) and [connect] the connected one (typed maybe-known [LocalAddress]).
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
        bufferFactory: BufferFactory,
    ): AddressedDatagramChannel {
        val local = LinuxSocketAddressResolver.resolve(localHost ?: WILDCARD_V4, localPort) as LinuxSocketAddress
        val fd = openDatagramSocket(local.family)
        // NO SO_REUSEADDR here — see [reuseAddrIsMulticastOnly]. Unicast binds must keep the kernel's
        // duplicate-bind check, or two sockets can share a port and the later binder steals the traffic.
        bindTo(fd, local)
        // Addressed contract: localAddress is non-null by construction, so an unreportable getsockname
        // fails fast HERE — before the channel exists and before IoUringManager counts the socket.
        val boundLocal =
            localAddressOf(fd) ?: run {
                close(fd)
                error("getsockname failed for bound UDP socket")
            }
        return AddressedIoUringDatagramChannel(
            fd = fd,
            localAddress = boundLocal,
            ipv6 = local.family == AddressFamily.IPv6,
            receiveBufferSize = receiveBufferSize,
            bufferFactory = bufferFactory,
        ).also { IoUringManager.onSocketOpened() }
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): ConnectedDatagramChannel {
        val peer = resolve(remoteHost, remotePort) as LinuxSocketAddress
        val fd = openDatagramSocket(peer.family)
        // NO SO_REUSEADDR here — see [reuseAddrIsMulticastOnly].
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
        // Connected contract: localAddress is the typed maybe-known state — a failing getsockname
        // degrades to LocalAddress.Unknown rather than aborting a perfectly usable connected socket.
        val local = localAddressOf(fd)?.let { LocalAddress.of(it) } ?: LocalAddress.Unknown
        return ConnectedIoUringDatagramChannel(
            fd = fd,
            peer = peer,
            localAddress = local,
            ipv6 = peer.family == AddressFamily.IPv6,
            receiveBufferSize = receiveBufferSize,
            bufferFactory = bufferFactory,
        ).also { IoUringManager.onSocketOpened() }
    }

    actual suspend fun bindMulticast(
        port: Int,
        family: AddressFamily,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): MulticastDatagramChannel {
        val v6 = family == AddressFamily.IPv6
        val wildcard = if (v6) WILDCARD_V6 else WILDCARD_V4
        val local = LinuxSocketAddressResolver.resolve(wildcard, port) as LinuxSocketAddress
        val fd = openDatagramSocket(local.family)
        // SO_REUSEADDR + SO_REUSEPORT: several multicast listeners can share the group's well-known port.
        setReuseAddr(fd)
        setReusePort(fd)
        bindTo(fd, local)
        // Same addressed fail-fast as bind(): no channel exists until the bound endpoint is known.
        val boundLocal =
            localAddressOf(fd) ?: run {
                close(fd)
                error("getsockname failed for bound UDP socket")
            }
        val base =
            AddressedIoUringDatagramChannel(
                fd = fd,
                localAddress = boundLocal,
                ipv6 = v6,
                receiveBufferSize = receiveBufferSize,
                bufferFactory = bufferFactory,
            )
        IoUringManager.onSocketOpened()
        return MulticastIoUringDatagramChannel(ipv6 = v6, base = base)
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

    /**
     * `SO_REUSEADDR` is **multicast-only** — set it in [bindMulticast] and nowhere else. This is the
     * contract the public [UdpSocket.bindMulticast] KDoc states, and what the JVM actual already does.
     *
     * On Linux it must never touch a *unicast* socket. Unlike Darwin (where `SO_REUSEADDR` alone still
     * refuses a duplicate unicast bind), Linux relaxes the duplicate-bind check for UDP, and two
     * consequences follow — both measured on 6.18 x86_64 and 6.18 aarch64, with the plain arm as control:
     *
     *  1. a second socket can bind an addr:port a first already holds (plain bind: `EADDRINUSE`), and
     *     the **later** binder then receives the unicast datagrams — the original owner goes silent;
     *  2. `bind(port = 0)` will hand out an **already-allocated ephemeral port**: 5/5 runs collided
     *     within a few hundred binds, versus 0/5 without the option.
     *
     * Together that is a live wedge on a busy host: a QUIC server binds port 0, an unrelated socket is
     * handed the same port, and the server never sees the client's Initial — the client observes total
     * silence and dies at its idle timeout. UDP has no `TIME_WAIT`, so a unicast bind gains nothing from
     * the option in exchange. Guarded by `LinuxUdpBindConformanceTests`.
     */
    private fun setReuseAddr(fd: Int) {
        memScoped {
            val v = alloc<IntVar>()
            v.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, v.ptr, sizeOf<IntVar>().convert())
        }
    }

    private fun setReusePort(fd: Int) {
        memScoped {
            val v = alloc<IntVar>()
            v.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, v.ptr, sizeOf<IntVar>().convert())
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
