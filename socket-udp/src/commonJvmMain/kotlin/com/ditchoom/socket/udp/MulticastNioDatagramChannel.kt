package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketOption
import java.net.StandardSocketOptions
import java.nio.channels.MembershipKey
import java.nio.channels.DatagramChannel as NioChannel

/**
 * JVM/Android [MulticastDatagramChannel]. The data plane (`receive`/`send`/`close`/`localAddress`) is a
 * plain [NioDatagramChannel] over the same [NioChannel] (delegated via `by base`); this class adds only the
 * NIO multicast control plane — [NioChannel.join] → [MembershipKey] and the `IP_MULTICAST_*` socket
 * options. The channel MUST have been opened with a [java.net.StandardProtocolFamily] (see
 * [UdpSocket.bindMulticast]) or `join` would throw; that is exactly why multicast is a distinct
 * construction, not a flag on an already-open unicast socket.
 *
 * Membership keys are tracked by (group, interface) so [leaveGroup] can drop the right one; [close]
 * (delegated to [base]) closes the channel, which the JDK auto-drops all memberships on.
 */
@ExperimentalDatagramApi
internal class MulticastNioDatagramChannel(
    private val channel: NioChannel,
    private val base: NioDatagramChannel,
) : MulticastDatagramChannel,
    DatagramChannel by base {
    /** (group address, interface) → live membership, so a leave targets the exact join. */
    private val memberships = mutableMapOf<MembershipPair, MembershipKey>()

    override val capabilities: DatagramCapabilities = base.capabilities.withMulticast()

    override suspend fun joinGroup(membership: MulticastMembership) {
        val group = membership.group.toInetAddress()
        val nif = resolveInterface(membership.networkInterface, group)
        val key = MembershipPair(group, nif)
        try {
            // NIO has no all-interfaces join: a concrete NetworkInterface is required.
            val membershipKey = channel.join(group, nif)
            memberships[key] = membershipKey
        } catch (t: Throwable) {
            throw MulticastException("joinGroup ${membership.group.host} on $nif failed", t)
        }
    }

    override suspend fun leaveGroup(membership: MulticastMembership) {
        val group = membership.group.toInetAddress()
        val nif = resolveInterface(membership.networkInterface, group)
        val membershipKey =
            memberships.remove(MembershipPair(group, nif))
                ?: throw MulticastException("leaveGroup ${membership.group.host} on $nif: not joined")
        try {
            membershipKey.drop()
        } catch (t: Throwable) {
            throw MulticastException("leaveGroup ${membership.group.host} on $nif failed", t)
        }
    }

    override suspend fun setTimeToLive(ttl: Int) = setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl, "setTimeToLive($ttl)")

    override suspend fun setLoopbackEnabled(enabled: Boolean) =
        // NIO normalizes IP_MULTICAST_LOOP so `true` means loopback ENABLED (the raw setsockopt sense is
        // inverted; the JDK hides that). Enabled is the kernel default and what same-host tests need.
        setOption(StandardSocketOptions.IP_MULTICAST_LOOP, enabled, "setLoopbackEnabled($enabled)")

    override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
        val nif =
            resolveInterface(networkInterface, group = null)
                ?: throw MulticastException("setOutboundInterface: Default has no concrete NIO interface")
        setOption(StandardSocketOptions.IP_MULTICAST_IF, nif, "setOutboundInterface")
    }

    private fun <T> setOption(
        option: SocketOption<T>,
        value: T,
        label: String,
    ) {
        try {
            channel.setOption(option, value)
        } catch (t: Throwable) {
            throw MulticastException("$label failed", t)
        }
    }

    /**
     * Resolve a [MulticastInterface] to a concrete [NetworkInterface]. [Default] returns a sensible
     * multicast-capable interface for [group]'s family (a real up NIC if one exists, else loopback) — NIO
     * `join` cannot take "any". A missing named/indexed interface is a typed [MulticastException].
     */
    private fun resolveInterface(
        iface: MulticastInterface,
        group: InetAddress?,
    ): NetworkInterface =
        when (iface) {
            is MulticastInterface.ByName ->
                NetworkInterface.getByName(iface.name)
                    ?: throw MulticastException("no interface named '${iface.name}'")
            is MulticastInterface.ByIndex ->
                NetworkInterface.getByIndex(iface.index)
                    ?: throw MulticastException("no interface at index ${iface.index}")
            MulticastInterface.Default -> defaultMulticastInterface(group)
        }

    private fun defaultMulticastInterface(group: InetAddress?): NetworkInterface {
        val wantV6 = group?.let { it is java.net.Inet6Address } ?: false
        val all = NetworkInterface.getNetworkInterfaces().toList()

        fun NetworkInterface.hasFamily(): Boolean = inetAddresses.asSequence().any { (it is java.net.Inet6Address) == wantV6 }

        val candidates = all.filter { runCatching { it.isUp && it.supportsMulticast() }.getOrDefault(false) }
        return candidates.firstOrNull { !it.isLoopback && it.hasFamily() }
            ?: candidates.firstOrNull { it.isLoopback }
            ?: candidates.firstOrNull()
            ?: throw MulticastException("no multicast-capable interface available")
    }

    private fun SocketAddress.toInetAddress(): InetAddress = toInetSocketAddress().address

    private data class MembershipPair(
        val group: InetAddress,
        val nif: NetworkInterface,
    )
}
