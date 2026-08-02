package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The §5 predicates and the [ReachResolution] pairing rules as **exhaustive tables**.
 *
 * Written as a table rather than one test per case on purpose: these four predicates are the entire
 * consumer-facing API of [NetworkState], and the property that matters is that they are *total and
 * mutually consistent* over every representable state. A table makes a newly added rung show up as a
 * missing row rather than as a silently-wrong default.
 */
class NetworkStateTests {
    private val wifi: NetworkId = NetworkId.Link(NetworkKind.Wifi, handle = 1)

    private val portal = InternetAccess.Observed.Blocked(BlockReason.CaptivePortal)
    private val suspended = InternetAccess.Observed.Blocked(BlockReason.Suspended)

    /** Every representable [NetworkState], one per distinct predicate outcome. */
    private val everyState: List<NetworkState> =
        listOf(
            NetworkState.Unknown,
            NetworkState.Offline,
            NetworkState.LinkLocal(wifi),
            NetworkState.Routable(wifi, InternetAccess.Observed.Confirmed),
            NetworkState.Routable(wifi, InternetAccess.Observed.Pending),
            NetworkState.Routable(wifi, InternetAccess.Observed.Limited),
            NetworkState.Routable(wifi, portal),
            NetworkState.Routable(wifi, suspended),
            NetworkState.Routable(wifi, InternetAccess.Unobserved),
        )

    @Test
    fun canRouteOffLinkIsEveryRoutableStateThatIsNotBlocked() {
        assertEquals(
            listOf(
                false, // Unknown           — nothing to attempt yet
                false, // Offline
                false, // LinkLocal         — no default route, nothing is reachable off-link
                true, //  Confirmed
                true, //  Pending           — the deliberate optimism: RFC §6's judgement call
                true, //  Limited           — the probe failed, YOUR destination may not have
                false, // Blocked(Portal)
                false, // Blocked(Suspended)
                true, //  Unobserved        — never observed, so assume reachable
            ),
            everyState.map { it.canRouteOffLink },
        )
    }

    @Test
    fun supportsLinkLocalIsExactlyThereIsALinkUp() {
        assertEquals(
            listOf(false, false, true, true, true, true, true, true, true),
            everyState.map { it.supportsLinkLocal },
        )
        // ...which is precisely `is Up`, so mDNS/multicast viability needs no `when`.
        assertEquals(everyState.map { it is NetworkState.Up }, everyState.map { it.supportsLinkLocal })
    }

    @Test
    fun needsUserActionIsOnlyACaptivePortal() {
        assertEquals(
            listOf(false, false, false, false, false, false, true, false, false),
            everyState.map { it.needsUserAction },
        )
    }

    @Test
    fun isTransientIsUnknownPendingAndSuspended() {
        assertEquals(
            listOf(
                true, //  Unknown           — no observation yet; wait, don't declare failure
                false, // Offline           — a real answer: act on it
                false, // LinkLocal
                false, // Confirmed
                true, //  Pending           — the ~0.7-1s validation window
                false, // Limited           — a settled verdict, not a window
                false, // Blocked(Portal)   — needs a human, will not resolve alone
                true, //  Blocked(Suspended) — data paused; wait
                false, // Unobserved        — terminal by construction
            ),
            everyState.map { it.isTransient },
        )
    }

    /**
     * The distinction that pays for the RFC: a validation window, a suspended link and a genuine network
     * change used to be indistinguishable, so auto-migration reacted to all three identically. Only one
     * of them should cause a tear-down.
     */
    @Test
    fun transientStatesAreDistinguishableFromARealChange() {
        val validating = NetworkState.Routable(wifi, InternetAccess.Observed.Pending)
        val paused = NetworkState.Routable(wifi, suspended)
        val genuineChange = NetworkState.Routable(NetworkId.Link(NetworkKind.Cellular, 2), InternetAccess.Observed.Confirmed)

        assertTrue(validating.isTransient && paused.isTransient)
        assertFalse(genuineChange.isTransient)
        // And the two transient ones are on the SAME network, so an identity-keyed consumer ignores them.
        assertEquals(wifi, validating.networkId)
        assertEquals(wifi, paused.networkId)
    }

    @Test
    fun networkIdIsTotalAndNeverNull() {
        assertEquals(
            listOf(
                NetworkId.Unidentified, // Unknown
                NetworkId.Unidentified, // Offline
                wifi,
                wifi,
                wifi,
                wifi,
                wifi,
                wifi,
                wifi,
            ),
            everyState.map { it.networkId },
        )
    }

    // --- pairing rules -------------------------------------------------------------------------

    @Test
    fun routeAndInternetPermitsEveryObservedVerdictButNotUnobserved() {
        val r = ReachResolution.RouteAndInternet
        assertEquals(
            listOf(true, true, true, true, true, true, true, true, false),
            everyState.map { r.permits(it) },
        )
    }

    @Test
    fun routeOnlyPermitsOnlyUnobservedReachability() {
        val r = ReachResolution.RouteOnly
        assertEquals(
            listOf(true, true, true, false, false, false, false, false, true),
            everyState.map { r.permits(it) },
        )
    }

    /**
     * `LinkOnly` may not report [NetworkState.LinkLocal]: asserting "a link is up but there is no route
     * off it" *requires* route visibility, which Node and the browser do not have. They report the
     * optimistic rung instead — a working browser must not be downgraded to link-local, since browsers
     * route off-link and cannot multicast at all.
     */
    @Test
    fun linkOnlyPermitsNeitherLinkLocalNorAnyVerdict() {
        val r = ReachResolution.LinkOnly
        assertEquals(
            listOf(
                true, //  Unknown
                true, //  Offline
                false, // LinkLocal  — needs route visibility to assert
                false, // Confirmed
                false, // Pending
                false, // Limited
                false, // Blocked(Portal)
                false, // Blocked(Suspended)
                true, //  Unobserved — the optimistic rung it does report
            ),
            everyState.map { r.permits(it) },
        )
    }

    @Test
    fun assertedPermitsAnythingBecauseItNeverMeasured() {
        assertTrue(everyState.all { ReachResolution.Asserted.permits(it) })
    }

    @Test
    fun alwaysAvailableDeclaresThatItNeverLooked() {
        val monitor = NetworkMonitor.AlwaysAvailable
        // It still says "go" for a consumer that just wants to opt out of monitoring...
        assertTrue(monitor.state.value.canRouteOffLink)
        // ...but a consumer that gates on reachability can now detect that the value was never measured,
        // which it could not while this claimed plain availability.
        assertEquals(
            MonitorCapability(MonitorMechanism.Static, ReachResolution.Asserted),
            monitor.capability,
        )
        assertEquals(NetworkId.Unidentified, monitor.state.value.networkId)
    }

    @Test
    fun anUndeclaredMonitorIsNeverOverTrusted() {
        // A third-party monitor predating `capability` gets the LEAST capable resolution, not the most.
        val undeclared =
            object : NetworkMonitor {
                override val state = kotlinx.coroutines.flow.MutableStateFlow<NetworkState>(NetworkState.Unknown)

                override fun close() {}
            }
        assertEquals(MonitorMechanism.Unknown, undeclared.capability.mechanism)
        assertEquals(ReachResolution.LinkOnly, undeclared.capability.resolution)
    }
}
