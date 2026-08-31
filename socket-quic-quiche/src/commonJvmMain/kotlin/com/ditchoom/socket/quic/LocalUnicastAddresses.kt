package com.ditchoom.socket.quic

import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * JVM/Android: `NetworkInterface` enumeration, filtered to what a server can actually bind.
 *
 * `isUp` rather than every interface the OS lists: binding a down interface's address fails, and a
 * server that refuses to start because a disconnected adapter is still enumerated would be worse
 * than the defect this fixes. Link-local IPv6 is dropped here rather than in shared code because
 * `Inet6Address.isLinkLocalAddress` is the platform's own answer to a question the numeric string
 * cannot answer on its own.
 */
internal fun enumerateLocalUnicastAddresses(): List<String> =
    NetworkInterface
        .getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp }
        .flatMap { it.inetAddresses.asSequence() }
        .filterNot { it.isMulticastAddress }
        .filterNot { it is Inet6Address && it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        // A scoped literal ("fe80::1%lo0", and on some stacks "::1%1") is not a bind target.
        .map { it.substringBefore('%') }
        .toList()
