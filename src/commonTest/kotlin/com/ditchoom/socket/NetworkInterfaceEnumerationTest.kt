package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-platform invariants for [enumerateNetworkInterfaces] — runs on every target (including Apple
 * via build-apple CI, which is the only validation the blind `appleNativeImpl` `getifaddrs` actual
 * gets). Host-independent: browser/Wasm return an empty list by design, so we assert the *shape* of
 * whatever is returned rather than requiring any particular interface to exist.
 */
class NetworkInterfaceEnumerationTest {
    @Test
    fun enumerationReturnsWellFormedEntries() {
        val interfaces = enumerateNetworkInterfaces()
        for (nif in interfaces) {
            assertTrue(nif.name.isNotEmpty(), "every interface must have a name")
            // Addresses are numeric literals (no DNS): each is non-empty and looks like an IP (has a
            // ':' for IPv6 or a '.' for IPv4). Zone suffixes (fe80::1%en0) still satisfy this.
            for (address in nif.addresses) {
                assertTrue(address.isNotEmpty(), "an address literal must not be empty")
                assertTrue(
                    address.contains('.') || address.contains(':'),
                    "an address must be a numeric IPv4/IPv6 literal, was: $address",
                )
            }
        }
    }

    @Test
    fun loopbackIsPresentAndSelfConsistentWhereInterfacesAreEnumerable() {
        val interfaces = enumerateNetworkInterfaces()
        // On platforms that enumerate at all (everything but the browser/Wasm), the loopback interface
        // always exists — a good end-to-end signal the native scan actually parsed something.
        if (interfaces.isNotEmpty()) {
            val loopback = interfaces.firstOrNull { it.isLoopback }
            assertTrue(loopback != null, "an enumerable host must expose a loopback interface")
            assertTrue(
                loopback.addresses.any { it == "127.0.0.1" || it == "::1" || it.startsWith("::1") },
                "loopback must carry a loopback address, had: ${loopback.addresses}",
            )
        }
    }
}
