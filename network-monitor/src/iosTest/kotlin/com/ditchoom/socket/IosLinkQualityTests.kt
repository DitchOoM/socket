package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the per-family srcDir wiring from the other side: iOS has no public RSSI API, so its targets
 * must compile the honest-absence twin (`appleRssiDefaultImpl`) and declare
 * [LinkQualityResolution.None] — absent means *declared* absent, never a fabricated value.
 */
class IosLinkQualityTests {
    @Test
    fun iosDeclaresNoneBecauseThePublicApiHasNoRssi() {
        assertEquals(LinkQualityResolution.None, appleLinkQualityResolution)
    }
}
