package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-mapper tests for [appleLinkAddresses]: a path's interfaces and a `getifaddrs` scan → [LinkAddresses]. */
class AppleLinkAddressesTests {
    private val wifiOn = PathInterface(type = 1, index = 15u, name = "en0")
    private val cellularBeside = PathInterface(type = 2, index = 2u, name = "pdp_ip0")

    private val scan =
        listOf(
            info("lo0", 1, "127.0.0.1", "::1"),
            info("pdp_ip0", 2, "198.51.100.20"),
            info("en0", 15, "192.0.2.10", "fe80::a:b:c:d%en0"),
        )

    private fun info(
        name: String,
        index: Long,
        vararg addresses: String,
    ) = NetworkInterfaceInfo(
        name,
        InterfaceIndex(index),
        NetworkKind.Other(name),
        addresses.toList(),
        isUp = true,
        isLoopback =
            name == "lo0",
    )

    private fun address(literal: String) = (NumericAddress.parse(literal) as ParsedAddress.Address).address

    /** Cellular kept up beside Wi-Fi: its address belongs to the cellular link the state would name. */
    @Test
    fun everyLinkOnThePathCarriesItsOwnAddresses() {
        val view = appleLinkAddresses(listOf(wifiOn, cellularBeside), usesTypes = 3, interfaces = scan)
        assertEquals(
            LinkAddresses.Reported(
                mapOf(
                    NetworkId.Link(NetworkKind.Wifi, 15) to setOf(address("192.0.2.10"), address("fe80::a:b:c:d")),
                    NetworkId.Link(NetworkKind.Cellular, 2) to setOf(address("198.51.100.20")),
                ),
            ),
            view,
        )
        assertEquals(
            AddressOwner.Link(appleNetworkState(NwPathStatus.Satisfied, 2, 2u, "pdp_ip0", 3).networkId),
            view.ownerOf(address("198.51.100.20")),
            "the cellular link carrying the address must be the same NetworkId the state names cellular with",
        )
    }

    @Test
    fun aPathWithNoInterfaceHasNoLinks() {
        assertEquals(LinkAddresses.Reported(emptyMap()), appleLinkAddresses(emptyList(), usesTypes = 0, interfaces = scan))
    }
}
