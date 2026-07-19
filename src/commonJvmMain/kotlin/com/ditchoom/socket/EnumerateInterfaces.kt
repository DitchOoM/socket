package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkKind
import java.net.NetworkInterface

/**
 * JVM/Android actual for [enumerateNetworkInterfaces], from [NetworkInterface.getNetworkInterfaces].
 * Kind is [NetworkKind.Other] — a raw JDK interface scan cannot classify link type (see [JvmNetworkId]).
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> =
    try {
        NetworkInterface
            .getNetworkInterfaces()
            ?.asSequence()
            ?.map { nif ->
                val idx = nif.index
                NetworkInterfaceInfo(
                    name = nif.name,
                    index = if (idx >= 0) idx.toLong() else nif.name.hashCode().toLong(),
                    kind = NetworkKind.Other(nif.name),
                    addresses =
                        nif.inetAddresses
                            .asSequence()
                            .mapNotNull { it.hostAddress }
                            .filter { it.isNotEmpty() }
                            .toList(),
                    isUp = runCatching { nif.isUp }.getOrDefault(false),
                    isLoopback = runCatching { nif.isLoopback }.getOrDefault(false),
                )
            }?.toList()
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
