package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkKind
import java.net.NetworkInterface

/**
 * JVM/Android actual for [platformNetworkInterfaces], from [NetworkInterface.getNetworkInterfaces].
 * Kind is [NetworkKind.Other] — a raw JDK interface scan cannot classify link type (see [JvmNetworkId]).
 */
internal actual fun platformNetworkInterfaces(): List<NetworkInterfaceInfo> =
    try {
        NetworkInterface
            .getNetworkInterfaces()
            ?.asSequence()
            ?.map { nif ->
                val idx = nif.index
                NetworkInterfaceInfo(
                    name = nif.name,
                    index = InterfaceIndex(if (idx >= 0) idx.toLong() else nif.name.hashCode().toLong()),
                    kind = NetworkKind.Other(nif.name),
                    addresses =
                        nif.inetAddresses
                            .asSequence()
                            .mapNotNull { it.hostAddress }
                            .filter { it.isNotEmpty() }
                            .toList(),
                    isUp = flagOrGone { nif.isUp },
                    isLoopback = flagOrGone { nif.isLoopback },
                )
            }?.toList()
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

@Deprecated(
    "Blocks the calling thread while it reads the interface table. Call the suspend " +
        "listNetworkInterfaces(), which does the read on an IO dispatcher.",
    ReplaceWith("listNetworkInterfaces()", "com.ditchoom.socket.listNetworkInterfaces"),
)
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> = platformNetworkInterfaces()
