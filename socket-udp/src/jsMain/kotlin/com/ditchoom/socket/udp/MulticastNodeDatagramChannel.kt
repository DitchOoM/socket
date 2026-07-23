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
            throw MulticastException("joinGroup ${membership.group.host} failed: ${t.message}", t)
        }
    }

    override suspend fun leaveGroup(membership: MulticastMembership) {
        val iface = interfaceAddress(membership.networkInterface)
        try {
            socket.dropMembership(membership.group.host, iface)
        } catch (t: Throwable) {
            throw MulticastException("leaveGroup ${membership.group.host} failed: ${t.message}", t)
        }
    }

    override suspend fun setTimeToLive(ttl: Int) {
        require(ttl in 0..255) { "ttl out of range: $ttl" }
        try {
            socket.setMulticastTTL(ttl)
        } catch (t: Throwable) {
            throw MulticastException("setTimeToLive($ttl) failed: ${t.message}", t)
        }
    }

    override suspend fun setLoopbackEnabled(enabled: Boolean) {
        try {
            socket.setMulticastLoopback(enabled)
        } catch (t: Throwable) {
            throw MulticastException("setLoopbackEnabled($enabled) failed: ${t.message}", t)
        }
    }

    override suspend fun setOutboundInterface(networkInterface: MulticastInterface) {
        val iface =
            interfaceAddress(networkInterface)
                ?: throw MulticastException("setOutboundInterface(Default) is a no-op on Node; select a concrete interface")
        try {
            socket.setMulticastInterface(iface)
        } catch (t: Throwable) {
            throw MulticastException("setOutboundInterface failed: ${t.message}", t)
        }
    }

    /** Resolve a [MulticastInterface] to the address string Node wants, or `null` for the kernel default. */
    private fun interfaceAddress(iface: MulticastInterface): String? =
        when (iface) {
            MulticastInterface.Default -> null
            is MulticastInterface.ByName ->
                nodeInterfaceAddress(iface.name, wantV6 = ipv6)
                    ?: throw MulticastException("no ${if (ipv6) "IPv6" else "IPv4"} address on interface '${iface.name}'")
            is MulticastInterface.ByIndex ->
                throw MulticastException("interface by index is unsupported on Node; use ByName or Default")
        }
}
