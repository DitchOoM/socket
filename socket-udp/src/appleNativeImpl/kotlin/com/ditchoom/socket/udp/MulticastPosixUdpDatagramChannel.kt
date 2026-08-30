@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
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
 * to a plain [PosixUdpDatagramChannel] ([base]) — addressed, so sending to a group is an ordinary
 * `send(payload, to = groupAddress)`; this class adds the POSIX multicast control plane via the struct-free
 * `socket_mc_*` cinterop shims (see `nw_udp_helpers.h`). Every control op maps a failing `setsockopt` to a
 * typed [MulticastException] carrying `errno`.
 *
 * ## The control plane borrows the descriptor; it does not own one (#527)
 *
 * This class has no `fd` field, and that is the fix. It used to take the descriptor number alongside
 * [base] and `setsockopt` it directly, which made it the one user of that number nobody had counted:
 * [base]'s [LastOutHandoff] admits `receive`, `send` and `close`, and releases the descriptor when the last
 * of *those* leaves (#498, #507). A `joinGroup` in flight was invisible to that word, so `close()` could
 * see an empty channel, close the descriptor, and let the next `socket()`/`open()`/`accept()` anywhere in
 * the process take the number — and the `setsockopt` then landed on **that** socket. Not a failure: a
 * silent success against a stranger's socket, joining it to a group or rewriting its multicast TTL.
 *
 * So every control op goes through [PosixUdpDatagramChannel.withDescriptor], the same admission the data
 * plane passes. The descriptor is reachable only inside that borrow, so it cannot be released underneath a
 * `setsockopt`, and a call that arrives once the channel is closed is refused with
 * [MulticastException.ChannelClosed] before any syscall. The state stays in exactly one place — [base]'s —
 * because a second copy of "is it closed" here would be the same defect with an extra step.
 *
 * @param beforeAdmission Test seam: runs on entry to every control op, *before* this caller is admitted to
 *   the descriptor — the window #527 lived in, where `close()` may still be the last party out.
 *   `MulticastControlPlaneAdmissionTests` parks a caller here and runs `close()` around it. Production
 *   leaves the no-op default.
 * @param beforeSyscall Test seam: runs after admission and before the `socket_mc_*` call, to hold a
 *   *legitimately* admitted control op across a `close()` and prove the descriptor stays open under it.
 */
@ExperimentalDatagramApi
internal class MulticastPosixUdpDatagramChannel(
    private val ipv6: Boolean,
    private val base: PosixUdpDatagramChannel,
    private val beforeAdmission: suspend () -> Unit = {},
    private val beforeSyscall: suspend () -> Unit = {},
) : MulticastDatagramChannel,
    AddressedDatagramChannel by base {
    override val capabilities: DatagramCapabilities = base.capabilities.withMulticast()

    /** The `ipv6` flag the `socket_mc_*` shims take: 1 selects the `IPPROTO_IPV6` option, 0 the `IPPROTO_IP` one. */
    private val v6: Int = if (ipv6) 1 else 0

    override suspend fun joinGroup(membership: MulticastMembership) = membership(join = true, membership)

    override suspend fun leaveGroup(membership: MulticastMembership) = membership(join = false, membership)

    private suspend fun membership(
        join: Boolean,
        membership: MulticastMembership,
    ) {
        val operation = "${if (join) "joinGroup" else "leaveGroup"} ${membership.group.host}"
        control(
            operation = operation,
            failed = { detail ->
                if (join) {
                    MulticastException.JoinFailed(membership.group, membership.networkInterface, detail)
                } else {
                    MulticastException.LeaveFailed(membership.group, membership.networkInterface, detail)
                }
            },
        ) { fd ->
            // Resolved inside the borrow, not before it: a closed channel is refused ahead of every other
            // answer, so a stale call can never be reported as, say, a missing interface instead.
            val iface = resolveInterface(membership.networkInterface)
            memScoped {
                val addr = alloc<sockaddr_storage>()
                membership.group.writeSockaddr(addr)
                val sa = addr.ptr.reinterpret<sockaddr>()
                if (join) {
                    socket_mc_join(fd, sa, iface.ipv4Be, iface.ifindex)
                } else {
                    socket_mc_leave(fd, sa, iface.ipv4Be, iface.ifindex)
                }
            }
        }
    }

    override suspend fun setTimeToLive(ttl: Int) {
        require(ttl in 0..255) { "ttl out of range: $ttl" }
        option("setTimeToLive($ttl)") { fd -> socket_mc_set_ttl(fd, v6, ttl) }
    }

    override suspend fun setLoopbackEnabled(enabled: Boolean) =
        option("setLoopbackEnabled($enabled)") { fd -> socket_mc_set_loop(fd, v6, if (enabled) 1 else 0) }

    override suspend fun setOutboundInterface(networkInterface: MulticastInterface) =
        option("setOutboundInterface") { fd ->
            val iface = resolveInterface(networkInterface)
            socket_mc_set_if(fd, v6, iface.ipv4Be, iface.ifindex)
        }

    /** A control op whose OS-layer failure is a plain [MulticastException.OptionFailed] under the same label. */
    private suspend fun option(
        operation: String,
        syscall: (fd: Int) -> Int,
    ) = control(operation, { detail -> MulticastException.OptionFailed(operation, detail) }, syscall)

    /**
     * Runs one `socket_mc_*` call on [base]'s descriptor, borrowed through the admission every other user
     * of that descriptor passes. Three outcomes and no fourth: applied, refused because the channel is
     * closed (no syscall ran), or attempted and failed with an `errno` — read inside the borrow, before
     * anything on the way out can overwrite it.
     */
    private suspend fun control(
        operation: String,
        failed: (detail: String) -> MulticastException,
        syscall: (fd: Int) -> Int,
    ) {
        beforeAdmission()
        val use =
            base.withDescriptor { fd ->
                beforeSyscall()
                if (syscall(fd) == 0) Control.Applied else Control.Failed(errnoMessage())
            }
        val outcome =
            when (use) {
                PosixUdpDatagramChannel.DescriptorUse.Refused -> Control.Refused
                is PosixUdpDatagramChannel.DescriptorUse.Ran -> use.value
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
