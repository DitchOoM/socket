package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

/**
 * Node.js [MulticastDatagramChannel]. Data plane (`receive`/`send`/`close`/`localAddress`) delegates to a
 * plain [NodeDatagramChannel] over the same `dgram` socket (`by base`); this class adds Node's
 * first-class multicast control (`addMembership` / `setMulticastTTL` / `setMulticastLoopback` /
 * `setMulticastInterface`).
 *
 * Node names an interface by its **address string**, not by OS name/index — so [MulticastInterface.ByName]
 * is resolved to an address via `os.networkInterfaces()`, [MulticastInterface.Default] omits it (kernel
 * default), and [MulticastInterface.ByIndex] is an honest typed [MulticastException] (Node has no v4
 * interface-index API). A synchronous Node throw from any control call is wrapped as [MulticastException].
 */
@ExperimentalDatagramApi
internal class MulticastNodeDatagramChannel(
    private val socket: DgramSocket,
    private val base: NodeDatagramChannel,
    private val ipv6: Boolean,
) : MulticastDatagramChannel,
    DatagramChannel by base {
    override val capabilities: DatagramCapabilities = base.capabilities.withMulticast()

    override suspend fun joinGroup(membership: MulticastMembership) {
        val iface = interfaceAddress(membership.networkInterface)
        try {
            socket.addMembership(membership.group.host, iface)
        } catch (t: Throwable) {
            throw MulticastException.JoinFailed(membership.group, membership.networkInterface, t.message ?: "$t", t)
        }
    }

    override suspend fun leaveGroup(membership: MulticastMembership) {
        val iface = interfaceAddress(membership.networkInterface)
        try {
            socket.dropMembership(membership.group.host, iface)
        } catch (t: Throwable) {
            throw MulticastException.LeaveFailed(membership.group, membership.networkInterface, t.message ?: "$t", t)
        }
    }

    override suspend fun setTimeToLive(ttl: Int) {
        require(ttl in 0..255) { "ttl out of range: $ttl" }
        try {
            socket.setMulticastTTL(ttl)
        } catch (t: Throwable) {
            throw MulticastException.OptionFailed("setTimeToLive($ttl)", t.message ?: "$t", t)
        }
    }

    override suspend fun setLoopbackEnabled(enabled: Boolean) {
        try {
            socket.setMulticastLoopback(enabled)
        } catch (t: Throwable) {
            throw MulticastException.OptionFailed("setLoopbackEnabled($enabled)", t.message ?: "$t", t)
        }
    }

    override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
        // Default = kernel default: on Node that means leaving IP_MULTICAST_IF unset (a no-op), not an error.
        val iface = interfaceAddress(networkInterface) ?: return
        try {
            socket.setMulticastInterface(iface)
        } catch (t: Throwable) {
            throw MulticastException.OptionFailed("setOutboundInterface", t.message ?: "$t", t)
        }
    }

    /** Resolve a [MulticastInterface] to the address string Node wants, or `null` for the kernel default. */
    private fun interfaceAddress(iface: MulticastInterface): String? =
        when (iface) {
            MulticastInterface.Default -> null
            is MulticastInterface.ByName ->
                nodeInterfaceAddress(iface.name, wantV6 = ipv6)
                    ?: throw MulticastException.NoSuchInterface(iface)
            is MulticastInterface.ByIndex ->
                throw MulticastException.UnsupportedInterface(iface, "Node names interfaces by address; use ByName or Default")
        }
}
