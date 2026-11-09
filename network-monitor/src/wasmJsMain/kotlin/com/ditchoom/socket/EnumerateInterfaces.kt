package com.ditchoom.socket

/**
 * wasmJs actual for [platformNetworkInterfaces]. A browser page has no network-interface list, so
 * this is always empty (matching the JS-in-a-browser branch).
 */
internal actual fun platformNetworkInterfaces(): List<NetworkInterfaceInfo> = emptyList()

@Deprecated(
    "Blocks the calling thread while it reads the interface table. Call the suspend " +
        "listNetworkInterfaces(), which does the read on an IO dispatcher.",
    ReplaceWith("listNetworkInterfaces()", "com.ditchoom.socket.listNetworkInterfaces"),
)
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> = platformNetworkInterfaces()
