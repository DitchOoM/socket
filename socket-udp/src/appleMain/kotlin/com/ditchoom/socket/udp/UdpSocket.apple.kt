@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.nw.nw_udp_cancel
import com.ditchoom.socket.udp.nw.nw_udp_copy_local_sockaddr
import com.ditchoom.socket.udp.nw.nw_udp_copy_remote_sockaddr
import com.ditchoom.socket.udp.nw.nw_udp_create
import com.ditchoom.socket.udp.nw.nw_udp_set_state_handler
import com.ditchoom.socket.udp.nw.nw_udp_start
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IPPROTO_IPV6
import platform.posix.IPPROTO_UDP
import platform.posix.IPV6_V6ONLY
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Apple/K-N default: a native deterministic factory (ARC-managed `NSMutableData`). Both the
 * NWConnection receive callback and POSIX `recvfrom` copy into the payload's raw native memory, so
 * `BufferFactory.Default` on native (a non-native GC buffer) is unusable; this is the exact strategy
 * both Apple channels have always used (formerly `PlatformBuffer.allocateNative`).
 */
internal actual val defaultDatagramBufferFactory: BufferFactory = BufferFactory.deterministic()

/**
 * Apple/K-N [UdpSocket]. `connect()` opens an `nw_connection_t` (NWConnection UDP mode) — the connected,
 * NWPath-aware client path ([NwUdpDatagramChannel]); `bind()` opens a POSIX datagram socket — the
 * unconnected many-peer server path ([PosixUdpDatagramChannel]). This split mirrors the quiche Apple
 * datapath: a server binds one port and demuxes many peers (connectionless, which NWConnection's
 * point-to-point model doesn't fit), while a client benefits from NW's Wi-Fi↔cellular awareness.
 */
@ExperimentalDatagramApi
actual object UdpSocket {
    init {
        SocketAddress.installResolver(AppleSocketAddressResolver)
    }

    actual suspend fun bind(
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): DatagramChannel {
        // A wildcard (null host) bind is DUAL-STACK IPv6: bind `::` with IPV6_V6ONLY=0 so the socket
        // receives BOTH ::1 and IPv4 (as ::ffff:a.b.c.d v4-mapped). Apple's NWConnection UDP *client*
        // resolves a name like "localhost" to ::1 by preference on Darwin, so an IPv4-only server would
        // silently never receive its packets (the handshake idle-times-out) — this is what makes a quiche
        // server reachable from the NW client either way. A specific host keeps its address family.
        val wildcard = localHost == null
        val local = AppleSocketAddressResolver.resolve(localHost ?: WILDCARD_V6, localPort) as AppleSocketAddress
        val af = if (local.family == AddressFamily.IPv6) AF_INET6 else AF_INET
        val fd = socket(af, SOCK_DGRAM, IPPROTO_UDP)
        check(fd >= 0) { "socket(AF=$af, SOCK_DGRAM) failed" }
        setReuseAddr(fd)
        if (wildcard && local.family == AddressFamily.IPv6) setV6Only(fd, false)
        bindTo(fd, local)
        return PosixUdpDatagramChannel(fd, localAddressOf(fd), receiveBufferSize, bufferFactory)
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): DatagramChannel {
        // NWConnection assigns the local endpoint itself; localHost/localPort are advisory here (matching
        // the quiche NW client path, which does not bind a specific local address).
        val conn =
            nw_udp_create(remoteHost, remotePort.toString())
                ?: throw UdpConnectException("nw_udp_create($remoteHost:$remotePort) failed")
        try {
            // Await NW readiness cancellably: a caller's withTimeout (e.g. the QUIC connect timeout) then
            // actually interrupts a stuck connect, and invokeOnCancellation cancels the NWConnection so a
            // timed-out/cancelled connect never leaks the nw_connection_t (B4c/B4d). Terminal NW states
            // surface as a typed [UdpConnectException] instead of a bare error (B4e).
            suspendCancellableCoroutine<Unit> { continuation ->
                var resumed = false
                nw_udp_set_state_handler(conn) { state, _, _, desc ->
                    if (resumed) return@nw_udp_set_state_handler
                    when (state) {
                        STATE_READY -> {
                            resumed = true
                            continuation.resume(Unit)
                        }
                        STATE_FAILED, STATE_CANCELLED -> {
                            resumed = true
                            continuation.resumeWithException(
                                UdpConnectException(
                                    desc ?: "NW UDP connection to $remoteHost:$remotePort " +
                                        (if (state == STATE_FAILED) "failed" else "cancelled"),
                                ),
                            )
                        }
                        else -> {}
                    }
                }
                nw_udp_start(conn)
                continuation.invokeOnCancellation { nw_udp_cancel(conn) }
            }
        } catch (t: Throwable) {
            nw_udp_cancel(conn)
            throw t
        }
        val peer = copyConnectionSockaddr(conn, local = false) ?: (resolve(remoteHost, remotePort))
        val local = copyConnectionSockaddr(conn, local = true)
        return NwUdpDatagramChannel(conn, peer, local, receiveBufferSize, bufferFactory)
    }

    actual suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress = SocketAddress.resolve(host, port)

    private fun setReuseAddr(fd: Int) {
        memScoped {
            val v = alloc<IntVar>()
            v.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, v.ptr, sizeOf<IntVar>().convert())
        }
    }

    /** Toggle `IPV6_V6ONLY` (dual-stack when false). Best-effort — Darwin's default is already 0. */
    private fun setV6Only(
        fd: Int,
        enabled: Boolean,
    ) {
        memScoped {
            val v = alloc<IntVar>()
            v.value = if (enabled) 1 else 0
            setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, v.ptr, sizeOf<IntVar>().convert())
        }
    }

    private fun bindTo(
        fd: Int,
        local: AppleSocketAddress,
    ) {
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val len = local.writeSockaddr(addr)
            if (bind(fd, addr.ptr.reinterpret(), len) != 0) {
                close(fd)
                error("bind to ${local.host}:${local.port} failed")
            }
        }
    }

    private fun localAddressOf(fd: Int): SocketAddress? =
        memScoped {
            val addr = alloc<sockaddr_storage>()
            val len = alloc<UIntVar>() // socklen_t is uint32 on Darwin
            len.value = sizeOf<sockaddr_storage>().convert()
            if (getsockname(fd, addr.ptr.reinterpret(), len.ptr) != 0) return@memScoped null
            sockaddrToAppleSocketAddress(addr.ptr.reinterpret<sockaddr>())
        }

    private fun copyConnectionSockaddr(
        conn: platform.Network.nw_connection_t,
        local: Boolean,
    ): AppleSocketAddress? =
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val cap = sizeOf<sockaddr_storage>().toInt()
            val len =
                if (local) {
                    nw_udp_copy_local_sockaddr(conn, storage.ptr, cap)
                } else {
                    nw_udp_copy_remote_sockaddr(conn, storage.ptr, cap)
                }
            if (len <= 0) return@memScoped null
            sockaddrToAppleSocketAddress(storage.ptr.reinterpret<sockaddr>())
        }

    private const val WILDCARD_V6 = "::"

    // nw_connection_state_t values (nw_udp_helpers.h): 3=ready, 4=failed, 5=cancelled.
    private const val STATE_READY = 3
    private const val STATE_FAILED = 4
    private const val STATE_CANCELLED = 5
}

/** A `connect()` fault surfaced by the NWConnection state handler (terminal failed/cancelled). */
internal class UdpConnectException(
    message: String,
) : RuntimeException(message)
