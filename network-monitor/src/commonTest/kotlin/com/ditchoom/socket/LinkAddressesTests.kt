package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [NumericAddress.parse] and [LinkAddresses.ownerOf]. */
class LinkAddressesTests {
    private fun address(literal: String): NumericAddress = assertIs<ParsedAddress.Address>(NumericAddress.parse(literal)).address

    @Test
    fun everySpellingOfOneAddressIsOneAddress() {
        assertEquals(address("::1"), address("0:0:0:0:0:0:0:1"))
        assertEquals(address("fe80::1:2:3:4"), address("fe80::1:2:3:4%en0"))
        assertEquals(address("2001:DB8:F:6E13::41"), address("2001:db8:f:6e13:0:0:0:41"))
        assertEquals(address("198.51.100.20"), address("::ffff:198.51.100.20"))
        assertEquals(address("198.51.100.20"), address("::ffff:c633:6414"))
    }

    @Test
    fun anIpv4AddressPrintsAsDottedQuads() {
        assertEquals("198.51.100.20", address("198.51.100.20").toString())
        assertEquals("255.0.0.1", address("255.0.0.1").toString())
    }

    @Test
    fun distinctAddressesDiffer() {
        assertIs<NumericAddress.V6>(address("::"))
        assertEquals(false, address("192.0.2.7") == address("192.0.2.8"))
        assertEquals(false, address("::ffff:0:1") == address("::fffe:0:1"))
    }

    @Test
    fun aLiteralThatIsNotANumericAddressSaysSo() {
        for (literal in listOf(
            "",
            "example.com",
            "1.2.3",
            "256.1.1.1",
            "1.2.3.4.5",
            "1::2::3",
            "12345::",
            "::g",
            ":::",
            "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7",
        )) {
            assertEquals(ParsedAddress.NotNumeric(literal), NumericAddress.parse(literal), literal)
        }
    }

    @Test
    fun theLinkCarryingAnAddressOwnsIt() {
        val wifi = NetworkId.Link(NetworkKind.Wifi, 15)
        val cellular = NetworkId.Link(NetworkKind.Cellular, 2)
        val reported =
            LinkAddresses.Reported(
                mapOf(wifi to setOf(address("192.0.2.10")), cellular to setOf(address("198.51.100.20"))),
            )
        assertEquals(AddressOwner.Link(cellular), reported.ownerOf(address("::ffff:198.51.100.20")))
        assertEquals(AddressOwner.Link(wifi), reported.ownerOf(address("192.0.2.10")))
        assertEquals(AddressOwner.Unclaimed, reported.ownerOf(address("10.0.0.1")))
        assertEquals(AddressOwner.NotReported, LinkAddresses.NotReported.ownerOf(address("10.0.0.1")))
    }

    @Test
    fun aMonitorThatDeclaresNothingReportsNoAddresses() {
        val monitor =
            object : NetworkMonitor {
                override val state = NetworkMonitor.AlwaysAvailable.state

                override fun close() {}
            }
        assertEquals(LinkAddresses.NotReported, monitor.linkAddresses.value)
    }
}
