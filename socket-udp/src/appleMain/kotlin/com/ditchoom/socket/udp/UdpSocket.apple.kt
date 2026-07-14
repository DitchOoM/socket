@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

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
import kotlinx.coroutines.CompletableDeferred
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IPPROTO_UDP
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
    ): DatagramChannel {
        val local = AppleSocketAddressResolver.resolve(localHost ?: WILDCARD_V4, localPort) as AppleSocketAddress
        val af = if (local.family == AddressFamily.IPv6) AF_INET6 else AF_INET
        val fd = socket(af, SOCK_DGRAM, IPPROTO_UDP)
        check(fd >= 0) { "socket(AF=$af, SOCK_DGRAM) failed" }
        setReuseAddr(fd)
        bindTo(fd, local)
        return PosixUdpDatagramChannel(fd, localAddressOf(fd), receiveBufferSize)
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
    ): DatagramChannel {
        // NWConnection assigns the local endpoint itself; localHost/localPort are advisory here (matching
        // the quiche NW client path, which does not bind a specific local address).
        val conn =
            nw_udp_create(remoteHost, remotePort.toString())
                ?: error("nw_udp_create($remoteHost:$remotePort) failed")
        val ready = CompletableDeferred<Boolean>()
        nw_udp_set_state_handler(conn) { state, _, _, _ ->
            when (state) {
                STATE_READY -> ready.complete(true)
                STATE_FAILED, STATE_CANCELLED -> ready.complete(false)
                else -> {}
            }
        }
        nw_udp_start(conn)
        if (!ready.await()) {
            nw_udp_cancel(conn)
            error("NWConnection to $remoteHost:$remotePort failed")
        }
        val peer = copyConnectionSockaddr(conn, local = false) ?: (resolve(remoteHost, remotePort))
        val local = copyConnectionSockaddr(conn, local = true)
        return NwUdpDatagramChannel(conn, peer, local, receiveBufferSize)
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

    private const val WILDCARD_V4 = "0.0.0.0"

    // nw_connection_state_t values (nw_udp_helpers.h): 3=ready, 4=failed, 5=cancelled.
    private const val STATE_READY = 3
    private const val STATE_FAILED = 4
    private const val STATE_CANCELLED = 5
}
