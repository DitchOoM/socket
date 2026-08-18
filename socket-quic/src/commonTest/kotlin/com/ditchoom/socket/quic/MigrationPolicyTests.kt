package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The migration value types, asserted where they are cheapest to assert: construction.
 *
 * Every case here used to be representable. `migrate(localHost = null, localPort = 0)` meant "anywhere,
 * any port" while `migrate(localHost = "", localPort = 70000)` meant nothing at all and was accepted
 * just as readily; `Succeeded(null, 0)` was a success that named no endpoint. The `require`s below are
 * the whole difference, so they are worth a test each.
 */
class MigrationPolicyTests {
    @Test
    fun policyIsExhaustiveOverThreeCases() {
        val policies: List<MigrationPolicy> =
            listOf(MigrationPolicy.Forbidden, MigrationPolicy.Manual, MigrationPolicy.Automatic)
        // The `when` is the assertion: a fourth case would stop compiling here.
        val described =
            policies.map {
                when (it) {
                    MigrationPolicy.Forbidden -> "forbidden"
                    MigrationPolicy.Manual -> "manual"
                    MigrationPolicy.Automatic -> "automatic"
                }
            }
        assertEquals(listOf("forbidden", "manual", "automatic"), described)
    }

    @Test
    fun networkMonitorSourceSuppliedCarriesTheCallersMonitor() {
        val source: NetworkMonitorSource = NetworkMonitorSource.Supplied(NetworkMonitor.AlwaysAvailable)
        val resolved =
            when (source) {
                NetworkMonitorSource.ProcessDefault -> null
                is NetworkMonitorSource.Supplied -> source.monitor
            }
        assertEquals(NetworkMonitor.AlwaysAvailable, resolved)
    }

    @Test
    fun freshLocalEndpointIsTheDefaultTarget() {
        // The one request every platform serves, and what automatic migration always issues.
        val target: MigrationTarget = MigrationTarget.FreshLocalEndpoint
        assertTrue(target is MigrationTarget.FreshLocalEndpoint)
    }

    @Test
    fun localAddressRejectsABlankHost() {
        assertFailsWith<IllegalArgumentException> { MigrationTarget.LocalAddress("") }
        assertFailsWith<IllegalArgumentException> { MigrationTarget.LocalAddress("   ") }
    }

    @Test
    fun localEndpointRejectsBlankHostAndOutOfRangePorts() {
        assertFailsWith<IllegalArgumentException> { MigrationTarget.LocalEndpoint("", 443) }
        // Port 0 is the old "ephemeral" sentinel — now spelled LocalAddress, so it is rejected here.
        assertFailsWith<IllegalArgumentException> { MigrationTarget.LocalEndpoint("127.0.0.1", 0) }
        assertFailsWith<IllegalArgumentException> { MigrationTarget.LocalEndpoint("127.0.0.1", 65536) }
        assertFailsWith<IllegalArgumentException> { MigrationTarget.LocalEndpoint("127.0.0.1", -1) }
        assertEquals(65535, MigrationTarget.LocalEndpoint("127.0.0.1", 65535).port)
    }

    @Test
    fun quicLocalEndpointRejectsBlankHostAndOutOfRangePort() {
        assertFailsWith<IllegalArgumentException> { QuicLocalEndpoint("", 4433) }
        assertFailsWith<IllegalArgumentException> { QuicLocalEndpoint("127.0.0.1", 0) }
        assertFailsWith<IllegalArgumentException> { QuicLocalEndpoint("127.0.0.1", 65536) }
        assertEquals("127.0.0.1:4433", QuicLocalEndpoint("127.0.0.1", 4433).toString())
    }

    /**
     * The reactor branches on the *family*, not the leaf, so the grouping has to hold: every
     * "never will" outcome must be an [MigrationResult.Unmoved.Impossible] and every "not this time"
     * outcome an [MigrationResult.Unmoved.Failed]. Getting this wrong would either wedge the observer
     * on a recoverable failure or spin it on a permanent one.
     */
    @Test
    fun impossibleAndFailedAreDisjointFamiliesUnderUnmoved() {
        val impossible: List<MigrationResult.Unmoved.Impossible> =
            listOf(
                MigrationResult.Unmoved.Impossible.ServerConnection,
                MigrationResult.Unmoved.Impossible.PolicyForbids,
                MigrationResult.Unmoved.Impossible.PeerForbids,
                MigrationResult.Unmoved.Impossible.BackendCannotMigrate,
                MigrationResult.Unmoved.Impossible.ConnectionClosed,
            )
        val failed: List<MigrationResult.Unmoved.Failed> =
            listOf(
                MigrationResult.Unmoved.Failed.EndpointNotSelectable,
                MigrationResult.Unmoved.Failed.AlreadyInProgress,
                MigrationResult.Unmoved.Failed.NoSpareConnectionId,
                MigrationResult.Unmoved.Failed.LocalPathUnavailable(IllegalStateException("boom")),
                MigrationResult.Unmoved.Failed.ProbeRejected(-7),
                MigrationResult.Unmoved.Failed.PathNotValidated,
                MigrationResult.Unmoved.Failed.SwitchRejected(-3),
            )
        assertTrue(impossible.none { it is MigrationResult.Unmoved.Failed })
        assertTrue(failed.none { it is MigrationResult.Unmoved.Impossible })
        // Every leaf is reachable from the top-level dispatch the reactor writes, and none is Succeeded.
        val unmoved: List<MigrationResult> = impossible + failed
        assertEquals(12, unmoved.count { it !is MigrationResult.Succeeded })
    }

    /**
     * [QuicPathState.Failed] takes an [MigrationResult.Unmoved], not an [MigrationResult] — so a path
     * state that says "failed" while carrying a success cannot be written. This test exists to pin the
     * bound; if someone widens it, the `when` below stops being exhaustive and the test stops compiling.
     */
    @Test
    fun pathStateFailedCannotCarryASuccess() {
        val state: QuicPathState = QuicPathState.Failed(MigrationResult.Unmoved.Failed.PathNotValidated)
        val endpoint =
            when (state) {
                QuicPathState.Original -> null
                is QuicPathState.Probing -> state.endpoint
                is QuicPathState.Validated -> state.endpoint
                is QuicPathState.Migrated -> state.endpoint
                is QuicPathState.Failed ->
                    when (state.result) {
                        is MigrationResult.Unmoved.Impossible -> null
                        is MigrationResult.Unmoved.Failed -> null
                    }
            }
        assertEquals(null, endpoint)
    }
}
