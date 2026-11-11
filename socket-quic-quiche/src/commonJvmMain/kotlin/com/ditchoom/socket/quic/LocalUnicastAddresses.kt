package com.ditchoom.socket.quic

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** What [enumerateLocalUnicastAddresses] reads of one interface; [JdkInterface] is the production view. */
internal interface LocalInterface {
    /** Whether the interface is up. Throws [SocketException] when the interface no longer exists. */
    val isUp: Boolean
    val addresses: List<InetAddress>
}

private class JdkInterface(
    private val nif: NetworkInterface,
) : LocalInterface {
    override val isUp: Boolean get() = nif.isUp
    override val addresses: List<InetAddress> get() = nif.inetAddresses.toList()
}

/** An interface that disappeared between being listed and being queried is not up. */
internal fun LocalInterface.isUpOrGone(): Boolean =
    try {
        isUp
    } catch (_: SocketException) {
        false
    }

private fun jdkInterfaces(): Sequence<LocalInterface> =
    try {
        NetworkInterface
            .getNetworkInterfaces()
            ?.asSequence()
            ?.map(::JdkInterface)
            .orEmpty()
    } catch (_: SocketException) {
        emptySequence()
    }

/**
 * JVM/Android: the addresses of the up interfaces, filtered to what a server can actually bind.
 *
 * `isUp` rather than every interface the OS lists: binding a down interface's address fails, and a
 * server that refuses to start because a disconnected adapter is still enumerated would be worse
 * than the defect this fixes. An interface that vanishes mid-enumeration is skipped, and a failed
 * enumeration yields no addresses. Link-local IPv6 is dropped here rather than in shared code because
 * `Inet6Address.isLinkLocalAddress` is the platform's own answer to a question the numeric string
 * cannot answer on its own.
 */
internal fun enumerateLocalUnicastAddresses(interfaces: () -> Sequence<LocalInterface> = ::jdkInterfaces): List<String> =
    interfaces()
        .filter { it.isUpOrGone() }
        .flatMap { it.addresses.asSequence() }
        .filterNot { it.isMulticastAddress }
        .filterNot { it is Inet6Address && it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        // A scoped literal ("fe80::1%lo0", and on some stacks "::1%1") is not a bind target.
        .map { it.substringBefore('%') }
        .toList()
