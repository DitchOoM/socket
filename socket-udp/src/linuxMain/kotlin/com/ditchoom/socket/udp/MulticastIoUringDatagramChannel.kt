@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.udp.linux.socket_if_index
import com.ditchoom.socket.udp.linux.socket_if_ipv4_be
import com.ditchoom.socket.udp.linux.socket_mc_join
import com.ditchoom.socket.udp.linux.socket_mc_leave
import com.ditchoom.socket.udp.linux.socket_mc_set_if
import com.ditchoom.socket.udp.linux.socket_mc_set_loop
import com.ditchoom.socket.udp.linux.socket_mc_set_ttl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.sockaddr_storage
import platform.posix.strerror

/**
 * Linux/K-N [MulticastDatagramChannel]. Data plane (`receive`/`send`/`close`/`localAddress`) delegates to
 * an [AddressedIoUringDatagramChannel] over the same [fd] (`by base` — a group send is an ordinary
 * addressed `send(payload, to = groupAddress)`); this class adds the POSIX multicast control plane via
 * the struct-free `socket_mc_*` cinterop shims (see `UdpSockets.def`). Every control op maps a failing
 * `setsockopt` to a typed [MulticastException] carrying `errno`.
 */
@ExperimentalDatagramApi
internal class MulticastIoUringDatagramChannel(
    private val ipv6: Boolean,
    private val base: AddressedIoUringDatagramChannel,
    /**
     * Test seam: runs on entry to every control op, *before* this caller is admitted to [base]'s
     * descriptor. Lets a test park a caller in the window `close()` used to race. Production passes
     * nothing and pays an empty suspend call.
     */
    private val beforeAdmission: suspend () -> Unit = {},
) : MulticastDatagramChannel,
    AddressedDatagramChannel by base {
    override val capabilities: DatagramCapabilities = base.capabilities.withMulticast()

    override suspend fun joinGroup(membership: MulticastMembership) = membership(join = true, membership)

    override suspend fun leaveGroup(membership: MulticastMembership) = membership(join = false, membership)

    private suspend fun membership(
        join: Boolean,
        membership: MulticastMembership,
    ) {
        val iface = resolveInterface(membership.networkInterface)
        val operation = if (join) "joinGroup" else "leaveGroup"
        control(operation, { detail ->
            if (join) {
                MulticastException.JoinFailed(membership.group, membership.networkInterface, detail)
            } else {
                MulticastException.LeaveFailed(membership.group, membership.networkInterface, detail)
            }
        }) { fd ->
            memScoped {
                val addr = alloc<sockaddr_storage>()
                membership.group.writeSockaddr(addr)
                // Bare reinterpret(): the cinterop C funcs take the def's OWN `sockaddr` (UdpSockets.def has
                // no headerFilter, so it generates its own type) — let inference pick it, as socket_bind does.
                if (join) {
                    socket_mc_join(fd, addr.ptr.reinterpret(), iface.ipv4Be, iface.ifindex)
                } else {
                    socket_mc_leave(fd, addr.ptr.reinterpret(), iface.ipv4Be, iface.ifindex)
                }
            }
        }
    }

    override suspend fun setTimeToLive(ttl: Int) {
        require(ttl in 0..255) { "ttl out of range: $ttl" }
        option("setTimeToLive($ttl)") { fd -> socket_mc_set_ttl(fd, if (ipv6) 1 else 0, ttl) }
    }

    override suspend fun setLoopbackEnabled(enabled: Boolean) {
        option("setLoopbackEnabled($enabled)") { fd ->
            socket_mc_set_loop(fd, if (ipv6) 1 else 0, if (enabled) 1 else 0)
        }
    }

    override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
        val iface = resolveInterface(networkInterface)
        option("setOutboundInterface") { fd -> socket_mc_set_if(fd, if (ipv6) 1 else 0, iface.ipv4Be, iface.ifindex) }
    }

    private suspend fun option(
        operation: String,
        syscall: (fd: Int) -> Int,
    ) = control(operation, { detail -> MulticastException.OptionFailed(operation, detail) }, syscall)

    /**
     * Runs one `socket_mc_*` call on [base]'s descriptor, borrowed through the admission every other user
     * of that descriptor passes (#526/#527). Three outcomes and no fourth: applied, refused because the
     * channel is closed (no syscall ran, so no recycled descriptor number was ever named), or attempted
     * and failed with an `errno` — read inside the borrow, before anything on the way out can overwrite it.
     *
     * This class used to hold the descriptor number in a field of its own and `setsockopt` it directly,
     * which is the one user of that number that `close()` could not see coming.
     */
    private suspend fun control(
        operation: String,
        failed: (detail: String) -> MulticastException,
        syscall: (fd: Int) -> Int,
    ) {
        beforeAdmission()
        val use = base.withDescriptor { fd -> if (syscall(fd) == 0) Control.Applied else Control.Failed(errnoMessage()) }
        val outcome =
            when (use) {
                IoUringDatagramChannelCore.DescriptorUse.Refused -> Control.Refused
                is IoUringDatagramChannelCore.DescriptorUse.Ran -> use.value
            }
        when (outcome) {
            Control.Applied -> Unit
            Control.Refused -> throw MulticastException.ChannelClosed(operation)
            is Control.Failed -> throw failed(outcome.detail)
        }
    }

    /** What one control-plane call produced. */
    private sealed interface Control {
        /** The `setsockopt` returned 0. */
        data object Applied : Control

        /** The channel was closed before this call was admitted: no descriptor was named, no syscall ran. */
        data object Refused : Control

        /** The `setsockopt` ran on this channel's own descriptor and failed; [detail] is its `errno`. */
        data class Failed(
            val detail: String,
        ) : Control
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
                if (idx == 0u) throw MulticastException.NoSuchInterface(iface)
                ResolvedInterface(ipv4Be = socket_if_ipv4_be(idx), ifindex = idx)
            }
            is MulticastInterface.ByIndex -> {
                val idx = iface.index.toUInt()
                ResolvedInterface(ipv4Be = socket_if_ipv4_be(idx), ifindex = idx)
            }
        }

    private fun errnoMessage(): String = "errno=$errno ${strerror(errno)?.toKString() ?: ""}"
}
