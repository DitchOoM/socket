package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the per-family srcDir wiring: the macOS targets must compile the CoreWLAN sampler
 * (`appleRssiMacosImpl`), whose declaration is [LinkQualityResolution.Rssi]. If the build wiring ever
 * routes macOS to the honest-absence twin, this fails — the live test alone could not catch it,
 * because it asserts against the same constant the wiring selects.
 */
class MacosLinkQualityTests {
    @Test
    fun macosDeclaresRssiViaCoreWlan() {
        assertEquals(LinkQualityResolution.Rssi, appleLinkQualityResolution)
    }
}
