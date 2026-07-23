@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.udp.nw.socket_if_index
import com.ditchoom.socket.udp.nw.socket_if_ipv4_be
import com.ditchoom.socket.udp.nw.socket_mc_join
import com.ditchoom.socket.udp.nw.socket_mc_leave
import com.ditchoom.socket.udp.nw.socket_mc_set_if
import com.ditchoom.socket.udp.nw.socket_mc_set_loop
import com.ditchoom.socket.udp.nw.socket_mc_set_ttl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.strerror

/**
 * Apple/K-N [MulticastDatagramChannel]. Data plane (`receive`/`send`/`close`/`localAddress`) is delegated
 * to a plain [PosixUdpDatagramChannel] over the same [fd] (`by base`); this class adds the POSIX multicast
 * control plane via the struct-free `socket_mc_*` cinterop shims (see `nw_udp_helpers.h`). Every control
 * op maps a failing `setsockopt` to a typed [MulticastException] carrying `errno`.
 */
@ExperimentalDatagramApi
internal class MulticastPosixUdpDatagramChannel(
    private val fd: Int,
    private val ipv6: Boolean,
    private val base: PosixUdpDatagramChannel,
) : MulticastDatagramChannel,
    DatagramChannel by base {
    override val capabilities: DatagramCapabilities = base.capabilities.withMulticast()

    override suspend fun joinGroup(membership: MulticastMembership) = membership(join = true, membership)

    override suspend fun leaveGroup(membership: MulticastMembership) = membership(join = false, membership)

    private fun membership(
        join: Boolean,
        membership: MulticastMembership,
    ) {
        val iface = resolveInterface(membership.networkInterface)
        memScoped {
            val addr = alloc<sockaddr_storage>()
            membership.group.writeSockaddr(addr)
            val sa = addr.ptr.reinterpret<sockaddr>()
            val rc =
                if (join) {
                    socket_mc_join(fd, sa, iface.ipv4Be, iface.ifindex)
                } else {
                    socket_mc_leave(fd, sa, iface.ipv4Be, iface.ifindex)
                }
            if (rc != 0) {
                val op = if (join) "joinGroup" else "leaveGroup"
                throw MulticastException("$op ${membership.group.host} failed: ${errnoMessage()}")
            }
        }
    }

    override suspend fun setTimeToLive(ttl: Int) {
        require(ttl in 0..255) { "ttl out of range: $ttl" }
        if (socket_mc_set_ttl(fd, if (ipv6) 1 else 0, ttl) != 0) {
            throw MulticastException("setTimeToLive($ttl) failed: ${errnoMessage()}")
        }
    }

    override suspend fun setLoopbackEnabled(enabled: Boolean) {
        if (socket_mc_set_loop(fd, if (ipv6) 1 else 0, if (enabled) 1 else 0) != 0) {
            throw MulticastException("setLoopbackEnabled($enabled) failed: ${errnoMessage()}")
        }
    }

    override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
        val iface = resolveInterface(networkInterface)
        if (socket_mc_set_if(fd, if (ipv6) 1 else 0, iface.ipv4Be, iface.ifindex) != 0) {
            throw MulticastException("setOutboundInterface failed: ${errnoMessage()}")
        }
    }

    private class ResolvedInterface(
        val ipv4Be: UInt,
        val ifindex: UInt,
    )

    private fun resolveInterface(iface: MulticastInterface): ResolvedInterface =
        when (iface) {
            MulticastInterface.Default -> ResolvedInterface(ipv4Be = 0u, ifindex = 0u)
            is MulticastInterface.ByName -> {
                val idx = socket_if_index(iface.name)
                if (idx == 0u) throw MulticastException("no interface named '${iface.name}'")
                ResolvedInterface(ipv4Be = socket_if_ipv4_be(idx), ifindex = idx)
            }
            is MulticastInterface.ByIndex -> {
                val idx = iface.index.toUInt()
                ResolvedInterface(ipv4Be = socket_if_ipv4_be(idx), ifindex = idx)
            }
        }

    private fun errnoMessage(): String = "errno=$errno ${strerror(errno)?.toKString() ?: ""}"
}
