package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [acceptsNetworkUpdate], the pure accept/ignore gate on the pre-O request-based
 * callback path — the full decision matrix on the host JVM with no Robolectric.
 *
 * `AndroidNetworkMonitorRobolectricTests` proves the callback machinery consults the gate against the
 * real `ConnectivityManager`; this proves the table itself, one row per line of the function's
 * contract. Handles stand in for `Network` instances: `Network` equality is its netId and
 * `networkHandle` is derived from that same netId, so handle equality *is* network equality.
 */
class AndroidNetworkUpdateGateTests {
    private val wifi = 100L
    private val cellular = 101L

    @Test
    fun theDefaultNetworkIsAlwaysAccepted() {
        assertTrue(acceptsNetworkUpdate(false, candidateHandle = wifi, activeHandle = wifi, trackedHandle = null))
        assertTrue(acceptsNetworkUpdate(false, candidateHandle = wifi, activeHandle = wifi, trackedHandle = wifi))
    }

    @Test
    fun aConcurrentNonDefaultNetworkIsIgnored() {
        // The headline flap: Wi-Fi is the default and tracked; the concurrent cellular network's
        // chatter must not publish, or state alternates between two Links on a stable device and
        // pathChanges() drives QUIC into spurious migrations.
        assertFalse(acceptsNetworkUpdate(false, candidateHandle = cellular, activeHandle = wifi, trackedHandle = wifi))
    }

    @Test
    fun theTrackedNetworkIsAcceptedEvenWhenADifferentDefaultExists() {
        // Mid-switch, before the tracked network's replacement callback lands, state still names the
        // old network — an in-place change on it beats holding a stale value.
        assertTrue(acceptsNetworkUpdate(false, candidateHandle = wifi, activeHandle = cellular, trackedHandle = wifi))
    }

    @Test
    fun aNullDefaultFallsBackToTheTrackedNetwork() {
        // getActiveNetwork() is transiently null mid-handoff: the tracked network's in-place changes
        // still land, and an untracked network still cannot steal the state.
        assertTrue(acceptsNetworkUpdate(false, candidateHandle = wifi, activeHandle = null, trackedHandle = wifi))
        assertFalse(acceptsNetworkUpdate(false, candidateHandle = cellular, activeHandle = null, trackedHandle = wifi))
    }

    @Test
    fun withNoDefaultAndNothingTrackedTheFirstNetworkIsAccepted() {
        // Cold start: there is nothing to be loyal to, so accept rather than stay Offline forever. A
        // wrong first pick is corrected by the next callback once the default reappears.
        assertTrue(acceptsNetworkUpdate(false, candidateHandle = cellular, activeHandle = null, trackedHandle = null))
    }

    @Test
    fun onOPlusTheGateIsAPassthrough() {
        // registerDefaultNetworkCallback already scopes every callback to the single default network,
        // so there is nothing left to filter — even a row the pre-O gate would reject is accepted.
        assertTrue(acceptsNetworkUpdate(true, candidateHandle = cellular, activeHandle = wifi, trackedHandle = wifi))
        assertTrue(acceptsNetworkUpdate(true, candidateHandle = cellular, activeHandle = null, trackedHandle = null))
    }
}
