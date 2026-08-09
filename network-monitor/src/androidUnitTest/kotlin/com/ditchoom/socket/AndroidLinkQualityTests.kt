package com.ditchoom.socket

import android.net.NetworkCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [androidLinkQuality], the pure `getSignalStrength()` → [LinkQuality] mapper — on the
 * host JVM with no device. `AndroidNetworkMonitorRobolectricTests` proves the callback machinery
 * publishes through it and that the capability gate matches the API level.
 */
class AndroidLinkQualityTests {
    @Test
    fun aRealSignalStrengthPassesThroughUnedited() {
        assertEquals(LinkQuality.Rssi(-55), androidLinkQuality(-55))
        assertEquals(LinkQuality.Rssi(-90), androidLinkQuality(-90))
    }

    @Test
    fun theUnreadableCaseIsUnavailableNotZero() {
        // Below API 29 the getter does not exist; with no capabilities object there is nothing to read.
        assertEquals(LinkQuality.Unavailable, androidLinkQuality(null))
    }

    @Test
    fun thePlatformsUnspecifiedSentinelIsUnavailableNotAReading() {
        // A wired link, or an agent that never filled the field: the platform's own in-band "no
        // measurement" marker must never surface as a (spectacularly strong-looking) number.
        assertEquals(LinkQuality.Unavailable, androidLinkQuality(NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED))
    }
}
