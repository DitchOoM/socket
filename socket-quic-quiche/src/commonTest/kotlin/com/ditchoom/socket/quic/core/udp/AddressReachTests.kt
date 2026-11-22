package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which addresses are on the local network: the lines local-network protection draws, on documentation ranges. */
class AddressReachTests {
    private fun v4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) = PathKey(4, 443, 0L, (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong())

    private val docNet = AttachedPrefix(v4(192, 0, 2, 0), 24)
    private val docV6 = AttachedPrefix(PathKey(6, 0, 0x2001_0db8_0000_0001L, 0L), 64)
    private val attached = listOf(docNet, docV6)

    @Test
    fun anAddressInsideAnAttachedPrefixIsOnTheLinkWhateverItsRange() {
        assertEquals(AddressReach.OnLink, v4(192, 0, 2, 77).reachFrom(attached))
        assertEquals(AddressReach.Beyond, v4(192, 0, 3, 77).reachFrom(attached), "one bit past the /24")
        assertEquals(AddressReach.OnLink, PathKey(6, 443, 0x2001_0db8_0000_0001L, 0x42L).reachFrom(attached))
        assertEquals(AddressReach.Beyond, PathKey(6, 443, 0x2001_0db8_0000_0002L, 0x42L).reachFrom(attached))
    }

    @Test
    fun linkLocalMulticastAndBroadcastAreOnTheLinkWithNoPrefixAttached() {
        assertEquals(AddressReach.OnLink, v4(169, 254, 10, 1).reachFrom(emptyList()))
        assertEquals(AddressReach.OnLink, v4(224, 0, 0, 251).reachFrom(emptyList()))
        assertEquals(AddressReach.OnLink, v4(255, 255, 255, 255).reachFrom(emptyList()))
        assertEquals(AddressReach.OnLink, PathKey(6, 443, 0xFE80_0000_0000_0000UL.toLong(), 1L).reachFrom(emptyList()))
        assertEquals(AddressReach.OnLink, PathKey(6, 443, 0xFF02_0000_0000_0000UL.toLong(), 0xFBL).reachFrom(emptyList()))
    }

    @Test
    fun aPrivateRangeOutsideEveryAttachedPrefixIsPrivateNotOnTheLink() {
        assertEquals(AddressReach.Private, v4(10, 20, 30, 40).reachFrom(attached))
        assertEquals(AddressReach.Private, v4(172, 31, 0, 1).reachFrom(attached))
        assertEquals(AddressReach.Beyond, v4(172, 32, 0, 1).reachFrom(attached), "172.16/12 ends at 172.31")
        assertEquals(AddressReach.Private, v4(192, 168, 1, 1).reachFrom(attached))
        assertEquals(AddressReach.Private, PathKey(6, 443, 0xFD00_0000_0000_0001UL.toLong(), 1L).reachFrom(attached))
        assertEquals(AddressReach.OnLink, v4(10, 20, 30, 40).reachFrom(listOf(AttachedPrefix(v4(10, 20, 0, 0), 16))))
    }

    @Test
    fun loopbackAndTheInternetAreNotTheLocalNetwork() {
        assertEquals(AddressReach.Loopback, v4(127, 0, 0, 1).reachFrom(attached))
        assertEquals(AddressReach.Loopback, PathKey(6, 443, 0L, 1L).reachFrom(attached))
        assertEquals(AddressReach.Beyond, v4(203, 0, 113, 9).reachFrom(attached))
        assertEquals(AddressReach.Beyond, v4(198, 51, 100, 9).reachFrom(attached))
    }

    @Test
    fun anIpv4MappedAddressIsJudgedAsItsIpv4Address() {
        val mapped = PathKey(6, 443, 0L, (0xFFFFL shl 32) or v4(192, 0, 2, 9).lo)
        assertEquals(AddressReach.OnLink, mapped.reachFrom(attached))
    }

    @Test
    fun aPrefixOfNoLengthPutsNothingOnTheLink() {
        assertEquals(AddressReach.Beyond, v4(203, 0, 113, 9).reachFrom(listOf(AttachedPrefix(v4(0, 0, 0, 0), 0))))
    }
}
