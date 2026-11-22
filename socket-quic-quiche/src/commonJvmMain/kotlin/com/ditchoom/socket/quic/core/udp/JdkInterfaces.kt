package com.ditchoom.socket.quic.core.udp

import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/** The prefix of every address on an interface that is up and not loopback; none when the system will not list them. */
internal actual fun attachedPrefixes(): List<AttachedPrefix> =
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .map { AttachedPrefix(InetSocketAddress(it.address, 0).toPathKey(), it.networkPrefixLength.toInt()) }
            .toList()
    } catch (unlisted: SocketException) {
        emptyList()
    }
