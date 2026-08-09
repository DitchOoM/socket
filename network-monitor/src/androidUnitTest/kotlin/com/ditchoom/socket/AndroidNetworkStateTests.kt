package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [androidNetworkState], the pure capability-bits → [NetworkState] mapper — the whole
 * Android rung table of RFC_NETWORK_REACHABILITY §4, on the host JVM with no device and no Robolectric.
 *
 * `AndroidNetworkMonitorRobolectricTests` proves the callback machinery reaches this function; this
 * proves the table itself, including the **precedence** the table depends on, which a shadow-driven
 * test can only sample one point of at a time.
 */
class AndroidNetworkStateTests {
    private val wifi = NetworkId.Link(NetworkKind.Wifi, 441492361229L)

    private fun state(
        hasInternet: Boolean = true,
        hasValidated: Boolean = false,
        hasCaptivePortal: Boolean = false,
        notSuspended: Boolean = true,
        blockedForApp: Boolean = false,
    ) = androidNetworkState(wifi, hasInternet, hasValidated, hasCaptivePortal, notSuspended, blockedForApp)

    @Test
    fun noInternetCapabilityIsLinkLocal() {
        assertEquals(NetworkState.LinkLocal(wifi), state(hasInternet = false))
    }

    @Test
    fun internetWithoutValidatedIsTheValidationWindow() {
        // Measured on a Realme RMX3933: real Wi-Fi grants INTERNET ~0.7–1s before VALIDATED, 3/3
        // reassociations. Reporting that as plain "online" is the §1.1 bug.
        val s = state(hasValidated = false)
        assertEquals(NetworkState.Routable(wifi, InternetAccess.Observed.Pending), s)
        assertTrue(s.canRouteOffLink, "attempting during the window beats stalling every connection by ~1s")
        assertTrue(s.isTransient, "…and a consumer waits it out rather than treating it as a change")
    }

    @Test
    fun internetWithValidatedIsConfirmed() {
        assertEquals(NetworkState.Routable(wifi, InternetAccess.Observed.Confirmed), state(hasValidated = true))
    }

    @Test
    fun aCaptivePortalIsBlockedAndNeedsUserAction() {
        val s = state(hasCaptivePortal = true)
        assertEquals(NetworkState.Routable(wifi, InternetAccess.Observed.Blocked(BlockReason.CaptivePortal)), s)
        assertTrue(s.needsUserAction)
        assertFalse(s.canRouteOffLink)
        assertFalse(s.isTransient, "a portal window never closes on its own")
    }

    @Test
    fun aSuspendedLinkIsBlockedButTransient() {
        val s = state(notSuspended = false)
        assertEquals(NetworkState.Routable(wifi, InternetAccess.Observed.Blocked(BlockReason.Suspended)), s)
        assertTrue(s.isTransient, "wait — do not tear down (crbug.com/1120144)")
        assertFalse(s.canRouteOffLink)
        assertFalse(s.needsUserAction, "no human can un-suspend a cellular link")
    }

    /**
     * The defining scenario of the per-UID verdict: under Data Saver / restricted-background the
     * network's capabilities keep reading `INTERNET|VALIDATED` while the platform passes none of this
     * app's traffic. Mapping that to `Confirmed` is precisely the bug — the monitor would keep
     * reporting a fully working network while every socket the app opens fails.
     */
    @Test
    fun aPerUidBlockedVerdictBeatsValidated() {
        val s = state(hasValidated = true, blockedForApp = true)
        assertEquals(NetworkState.Routable(wifi, InternetAccess.Observed.Blocked(BlockReason.Suspended)), s)
        assertTrue(s.isTransient, "the platform promises a follow-up verdict — wait, do not tear down")
        assertFalse(s.canRouteOffLink, "retrying while blocked is futile")
        assertFalse(s.needsUserAction)
    }

    /** A portal needs a human; a per-UID pause only needs time. When both apply, the human wins. */
    @Test
    fun aPortalOutranksAPerUidBlockedVerdict() {
        assertEquals(
            InternetAccess.Observed.Blocked(BlockReason.CaptivePortal),
            (state(hasCaptivePortal = true, blockedForApp = true) as NetworkState.Routable).internet,
        )
    }

    /**
     * The precedence that makes the table correct, and the reason it is ordered by *what the consumer
     * must do*: a portal-intercepted network can legitimately also be `VALIDATED` on some builds, and a
     * suspended link keeps `INTERNET`. Reading `VALIDATED` first would report both as `Confirmed`.
     */
    @Test
    fun theTwoDoNotAttemptStatesBeatValidated() {
        assertEquals(
            InternetAccess.Observed.Blocked(BlockReason.CaptivePortal),
            (state(hasValidated = true, hasCaptivePortal = true) as NetworkState.Routable).internet,
        )
        assertEquals(
            InternetAccess.Observed.Blocked(BlockReason.Suspended),
            (state(hasValidated = true, notSuspended = false) as NetworkState.Routable).internet,
        )
    }

    /** A portal needs a human; a suspended link only needs time. When both bits are set, say so. */
    @Test
    fun aPortalOutranksASuspendedLink() {
        assertEquals(
            InternetAccess.Observed.Blocked(BlockReason.CaptivePortal),
            (state(hasCaptivePortal = true, notSuspended = false) as NetworkState.Routable).internet,
        )
    }

    /** No INTERNET wins over everything: without it nothing routes off-link, whatever else is set. */
    @Test
    fun linkLocalOutranksEveryReachabilityBit() {
        assertEquals(
            NetworkState.LinkLocal(wifi),
            state(hasInternet = false, hasValidated = true, hasCaptivePortal = true, notSuspended = false),
        )
    }

    @Test
    fun everyMappedStateIsPermittedByTheDeclaredResolution() {
        val states =
            listOf(
                state(hasInternet = false),
                state(),
                state(hasValidated = true),
                state(hasCaptivePortal = true),
                state(notSuspended = false),
                state(hasValidated = true, blockedForApp = true),
            )
        for (s in states) {
            assertTrue(
                ReachResolution.RouteAndInternet.permits(s),
                "AndroidNetworkMonitor declares RouteAndInternet but mapped $s",
            )
        }
    }

    /**
     * RFC §9.3: [InternetAccess.Observed.Limited] ships with no producer. Android's analogue,
     * `NET_CAPABILITY_PARTIAL_CONNECTIVITY`, is `@SystemApi` and absent from the public SDK at any
     * `compileSdk`, so this mapper cannot reach it — and a future change that made it reachable should
     * have to delete this test deliberately.
     */
    @Test
    fun androidNeverProducesLimited() {
        val combinations =
            listOf(true, false).flatMap { internet ->
                listOf(true, false).flatMap { validated ->
                    listOf(true, false).flatMap { portal ->
                        listOf(true, false).flatMap { notSuspended ->
                            listOf(true, false).map { blocked ->
                                androidNetworkState(wifi, internet, validated, portal, notSuspended, blocked)
                            }
                        }
                    }
                }
            }
        assertEquals(32, combinations.size, "the full 2^5 bit space")
        assertTrue(
            combinations.none { it is NetworkState.Routable && it.internet == InternetAccess.Observed.Limited },
            "Limited has no Android producer",
        )
    }
}
