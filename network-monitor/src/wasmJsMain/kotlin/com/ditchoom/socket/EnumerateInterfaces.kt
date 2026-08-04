package com.ditchoom.socket

/**
 * wasmJs actual for [enumerateNetworkInterfaces]. A browser page has no network-interface list, so
 * this is always empty (matching the JS-in-a-browser branch).
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> = emptyList()
