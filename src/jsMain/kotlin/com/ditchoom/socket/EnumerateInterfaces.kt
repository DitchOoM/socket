package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkKind

/**
 * JS actual for [enumerateNetworkInterfaces].
 *
 * - **Node.js**: `os.networkInterfaces()` — one [NetworkInterfaceInfo] per interface name, aggregating
 *   its address records. Node exposes no OS interface index, so [NetworkInterfaceInfo.index] is 0, and
 *   the kind is [NetworkKind.Other] (no link-kind API); loopback is derived from the `internal` flag.
 * - **Browser**: no interface list exists in a page — returns an empty list.
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> {
    if (!isNodeJs) return emptyList()
    return try {
        val interfaces = js("require('os').networkInterfaces()")
        val names: Array<String> = js("Object.keys")(interfaces) as Array<String>
        names.map { name ->
            val records = interfaces[name]
            val count = records.length as Int
            val addresses = mutableListOf<String>()
            var loopback = false
            for (i in 0 until count) {
                val record = records[i]
                val address = record.address as? String
                if (address != null && address.isNotEmpty()) addresses.add(address)
                if (record.internal as? Boolean == true) loopback = true
            }
            NetworkInterfaceInfo(
                name = name,
                index = InterfaceIndex(0L), // Node exposes no OS interface index
                kind = NetworkKind.Other(name),
                addresses = addresses,
                isUp = true, // Node lists only interfaces that are up
                isLoopback = loopback,
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
