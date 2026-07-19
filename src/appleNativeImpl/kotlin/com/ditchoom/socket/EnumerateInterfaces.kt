@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_enumerate_interfaces
import com.ditchoom.socket.transport.NetworkKind

/**
 * Apple actual for [enumerateNetworkInterfaces].
 *
 * The `getifaddrs` scan itself lives in the C helper `nw_helper_enumerate_interfaces` (see
 * `nw_helpers.h`) because `getifaddrs` / `struct ifaddrs` are **not** exposed by `platform.posix` on
 * Apple Kotlin/Native — only scalars and strings cross back into Kotlin (the same block-callback shape
 * `AppleNetworkMonitor` uses for `NWPathMonitor`). The callback fires synchronously once per
 * (interface, address) record, so each interface's fields are accumulated into ONE builder keyed by
 * name (no parallel maps to drift out of sync). [NetworkKind] is [NetworkKind.Other] — a raw
 * `getifaddrs` scan cannot classify link type (only the primary-path `AppleNetworkMonitor` gets a
 * semantic kind, from `NWPathMonitor`).
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> {
    val byName = LinkedHashMap<String, InterfaceAccumulator>()

    nw_helper_enumerate_interfaces { name, index, isUp, isLoopback, address ->
        if (!name.isNullOrEmpty()) {
            val acc =
                byName.getOrPut(name) {
                    InterfaceAccumulator(name, InterfaceIndex(index.toLong()), isUp != 0, isLoopback != 0)
                }
            if (!address.isNullOrEmpty()) acc.addresses.add(address)
        }
    }

    return byName.values.map { it.toInfo() }
}

/** Mutable per-interface record built up across the C `getifaddrs` callback (see [enumerateNetworkInterfaces]). */
private class InterfaceAccumulator(
    private val name: String,
    private val index: InterfaceIndex,
    private val isUp: Boolean,
    private val isLoopback: Boolean,
) {
    val addresses = mutableListOf<String>()

    fun toInfo() =
        NetworkInterfaceInfo(
            name = name,
            index = index,
            kind = NetworkKind.Other(name),
            addresses = addresses.toList(),
            isUp = isUp,
            isLoopback = isLoopback,
        )
}
