package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * A UDP [DatagramChannel] that additionally exposes IP **multicast** group control (RFC 1112 / RFC 2710
 * any-source multicast). Produced only by [UdpSocket.bindMulticast] — a plain [UdpSocket.bind] channel is
 * a bare [DatagramChannel] with no membership surface, so a socket that was never opened for multicast can
 * never be asked to join a group (no impossible states). Its [capabilities] report
 * [DatagramCapabilities.multicast] == `true`.
 *
 * The data plane (`receive`/`send`/`close`) is exactly a [DatagramChannel]'s: a received multicast
 * [com.ditchoom.buffer.flow.Datagram] carries the *sender's* unicast address as its `peer`, and sending to
 * a group is an ordinary `send(payload, to = groupAddress)`. This interface adds only the socket-lifetime
 * control plane that unicast UDP lacks:
 *
 * - [joinGroup] / [leaveGroup] — start / stop receiving a group's datagrams on an interface.
 * - [setTimeToLive] — bound how many hops *outbound* multicast may travel (`IP_MULTICAST_TTL` /
 *   `IPV6_MULTICAST_HOPS`).
 * - [setLoopbackEnabled] — whether datagrams this socket sends to a group it has joined are also delivered
 *   back to this host (`IP_MULTICAST_LOOP`; on by default, which is what same-host tests rely on).
 * - [setOutboundInterface] — which interface *outbound* multicast egresses (`IP_MULTICAST_IF`).
 *
 * Source-specific multicast (SSM, RFC 4607) is a deliberate follow-up; this first landing is any-source.
 *
 * Threading matches [DatagramChannel]: confine `receive` and `send` each to one coroutine. The control
 * operations are `suspend` so a platform that must hop to a socket-owning dispatcher (Apple) can, but they
 * are cheap `setsockopt`-class calls, not blocking I/O. Every control operation throws
 * [MulticastException] on failure — never a bare platform error string.
 */
@ExperimentalDatagramApi
interface MulticastDatagramChannel : DatagramChannel {
    /**
     * Begin receiving datagrams sent to [membership]'s group on its interface (`IP_ADD_MEMBERSHIP` /
     * `IPV6_JOIN_GROUP`). Joining a group already joined on the same interface is a no-op-or-error at the
     * OS layer surfaced as [MulticastException]; join distinct interfaces for the same group with distinct
     * [MulticastMembership]s.
     */
    suspend fun joinGroup(membership: MulticastMembership)

    /** Stop receiving [membership]'s group on its interface (`IP_DROP_MEMBERSHIP` / `IPV6_LEAVE_GROUP`). */
    suspend fun leaveGroup(membership: MulticastMembership)

    /**
     * Set the outbound multicast hop limit to [ttl] (`IP_MULTICAST_TTL` / `IPV6_MULTICAST_HOPS`), `0..255`.
     * `1` (the kernel default) confines traffic to the local subnet; `0` prevents egress past this host.
     */
    suspend fun setTimeToLive(ttl: Int)

    /**
     * Enable or disable local loopback of this socket's own outbound multicast (`IP_MULTICAST_LOOP`).
     * Default is enabled — required for a sender and a receiver on the *same* host (the same-host test
     * shape) to see each other's group datagrams.
     */
    suspend fun setLoopbackEnabled(enabled: Boolean)

    /** Select which interface outbound multicast egresses (`IP_MULTICAST_IF`). */
    suspend fun setOutboundInterface(networkInterface: MulticastInterface)
}

/**
 * An any-source multicast group membership: the [group] to receive, and the [networkInterface] to receive
 * it on. The [group]'s [SocketAddress.host] and [SocketAddress.family] name the group (its port is ignored
 * — the receiving port is fixed by [UdpSocket.bindMulticast]); resolve it via [UdpSocket.resolve].
 */
@ExperimentalDatagramApi
data class MulticastMembership(
    val group: SocketAddress,
    val networkInterface: MulticastInterface = MulticastInterface.Default,
)

/**
 * Which network interface a multicast operation targets. Kept a small sealed set so an unsupported form
 * fails to compile rather than at runtime:
 *
 * - [Default] — let the kernel pick from the routing table (`INADDR_ANY` / interface index `0`). Fully
 *   supported on every platform; the common case.
 * - [ByName] — an OS interface name, e.g. `"en0"` (Apple) / `"eth0"` (Linux). Resolved to an index /
 *   address per platform.
 * - [ByIndex] — an OS interface index (1-based; `if_nametoindex` / `NetworkInterface.getIndex`).
 */
sealed interface MulticastInterface {
    /** The kernel's default multicast interface (`INADDR_ANY` / ifindex 0). */
    data object Default : MulticastInterface

    /** By OS interface name (`"en0"`, `"eth0"`, `"lo"`/`"lo0"`). */
    data class ByName(
        val name: String,
    ) : MulticastInterface

    /** By OS interface index (1-based). */
    data class ByIndex(
        val index: Int,
    ) : MulticastInterface
}

/**
 * A multicast control-plane operation (join / leave / TTL / loopback / interface select) failed. Carries a
 * human-readable [message] and, where the platform surfaced one, the underlying [cause] — the typed seam a
 * consumer catches instead of matching on platform error strings.
 */
@ExperimentalDatagramApi
class MulticastException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The same platform capabilities with [DatagramCapabilities.multicast] flipped on — the one field a
 * multicast channel differs from its unicast sibling. */
@ExperimentalDatagramApi
internal fun DatagramCapabilities.withMulticast(): DatagramCapabilities =
    DatagramCapabilities(
        ecnSend = ecnSend,
        ecnReceive = ecnReceive,
        dscpSend = dscpSend,
        dontFragment = dontFragment,
        hopLimitSend = hopLimitSend,
        hopLimitReceive = hopLimitReceive,
        localAddressReceive = localAddressReceive,
        sourceAddressSelect = sourceAddressSelect,
        multicast = true,
    )
