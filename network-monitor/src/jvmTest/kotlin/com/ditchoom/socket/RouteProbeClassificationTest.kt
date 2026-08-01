package com.ditchoom.socket

import com.ditchoom.socket.RouteProbeOutcome.Indeterminate
import com.ditchoom.socket.RouteProbeOutcome.NoRoute
import com.ditchoom.socket.RouteProbeOutcome.Routed
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The full 2×3×3 matrix of [classifyRouteProbes] — pure, no I/O, so every combination the live probe can
 * produce is pinned here, including the ones no CI host can reach (a `SecurityManager` denying the
 * probe, a sandbox blocking UDP). The live probe itself is exercised by the netns harness
 * (`NetnsJvmProbe`), which can only reach the combinations its namespace topology produces.
 */
class RouteProbeClassificationTest {
    private val id = NetworkId.Link(NetworkKind.Other("eth0"), 2L)

    private fun classify(
        v4: RouteProbeOutcome,
        v6: RouteProbeOutcome,
        anyUp: Boolean = true,
    ) = classifyRouteProbes(anyInterfaceUp = anyUp, v4 = v4, v6 = v6, id = id)

    @Test
    fun noInterfaceUpIsOfflineRegardlessOfProbeOutcomes() {
        // The probes are meaningless without a link; Offline must win over any (stale) probe verdict.
        for (v4 in RouteProbeOutcome.entries) {
            for (v6 in RouteProbeOutcome.entries) {
                assertEquals(NetworkState.Offline, classify(v4, v6, anyUp = false), "offline must ignore $v4/$v6")
            }
        }
    }

    @Test
    fun anyRoutedFamilyIsRoutable() {
        // One family with a default route is a routable host — a v6-only or v4-only host must not be
        // downgraded because its other family (correctly) reports NoRoute, or could not probe at all.
        val expected = NetworkState.Routable(id, InternetAccess.Unobserved)
        for (other in RouteProbeOutcome.entries) {
            assertEquals(expected, classify(Routed, other), "v4 Routed must dominate v6 $other")
            assertEquals(expected, classify(other, Routed), "v6 Routed must dominate v4 $other")
        }
    }

    @Test
    fun linkLocalIsEarnedByExactlyBothFamiliesSayingNoRoute() {
        assertEquals(NetworkState.LinkLocal(id), classify(NoRoute, NoRoute))
        // LinkLocal requires route visibility (§9.2): of the nine up-link combinations, only the one
        // where the kernel was asked for both families and said no both times may claim it.
        val linkLocalCombos =
            RouteProbeOutcome.entries.flatMap { v4 ->
                RouteProbeOutcome.entries.mapNotNull { v6 ->
                    (v4 to v6).takeIf { classify(v4, v6) is NetworkState.LinkLocal }
                }
            }
        assertEquals(listOf(NoRoute to NoRoute), linkLocalCombos, "LinkLocal must be earned, never assumed")
    }

    @Test
    fun indeterminateWithoutRoutedStaysOptimisticallyRoutable() {
        // The regression this matrix guards: a host whose probe is denied (SecurityManager, sandboxed
        // UDP, Android missing INTERNET permission) routes fine and reported AVAILABLE on main. Blind
        // is not "no route" — it must keep the optimistic rung, not silently lose canRouteOffLink.
        val expected = NetworkState.Routable(id, InternetAccess.Unobserved)
        assertEquals(expected, classify(Indeterminate, Indeterminate))
        assertEquals(expected, classify(Indeterminate, NoRoute))
        assertEquals(expected, classify(NoRoute, Indeterminate))
        assertTrue(classify(Indeterminate, NoRoute).canRouteOffLink, "a blind probe must not fail closed")
        assertFalse(classify(NoRoute, NoRoute).canRouteOffLink, "an answered probe still fails closed")
    }

    @Test
    fun everyClassificationIsLegalUnderTheDeclaredRouteOnlyResolution() {
        // The monitors built on this resolver declare ReachResolution.RouteOnly; permits() is the
        // enforced pairing rule, so no cell of the matrix may emit a state the declaration forbids.
        for (anyUp in listOf(false, true)) {
            for (v4 in RouteProbeOutcome.entries) {
                for (v6 in RouteProbeOutcome.entries) {
                    val state = classify(v4, v6, anyUp)
                    assertTrue(
                        ReachResolution.RouteOnly.permits(state),
                        "RouteOnly must permit $state (anyUp=$anyUp, v4=$v4, v6=$v6)",
                    )
                }
            }
        }
    }

    @Test
    fun identityPropagatesIntoEveryUpState() {
        // Both Up rungs must carry the caller's identity untouched — it is what the capability cache and
        // QUIC auto-migration key on.
        assertEquals(id, classify(Routed, Indeterminate).networkId)
        assertEquals(id, classify(NoRoute, NoRoute).networkId)
        assertEquals(id, classify(Indeterminate, Indeterminate).networkId)
    }
}
