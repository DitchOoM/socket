package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for [androidLinkAddresses], the named network's `LinkProperties` addresses → [LinkAddresses]. */
class AndroidLinkAddressesTests {
    private fun address(literal: String) = (NumericAddress.parse(literal) as ParsedAddress.Address).address

    @Test
    fun theNamedNetworkCarriesItsLinkAddresses() {
        val cellular = NetworkId.Link(NetworkKind.Cellular, 432_902_426_637L)
        assertEquals(
            LinkAddresses.Reported(mapOf(cellular to setOf(address("198.51.100.30"), address("2001:db8:f:6e13::41")))),
            androidLinkAddresses(cellular, listOf("198.51.100.30", "2001:db8:f:6e13::41", "not-an-address")),
        )
    }

    @Test
    fun anUnidentifiedNetworkIsNoLink() {
        assertEquals(LinkAddresses.Reported(emptyMap()), androidLinkAddresses(NetworkId.Unidentified, listOf("192.0.2.6")))
    }
}
