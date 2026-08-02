package com.ditchoom.socket.transport.sim.fixtures

import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import com.ditchoom.socket.transport.sim.SimFixture
import com.ditchoom.socket.transport.sim.simFixture
import kotlin.time.Duration.Companion.seconds

/**
 * Golden 3 — `network-flap-reconnect` (RFC_DETERMINISTIC_SIMULATION.md §9 W2), the transport-tier
 * golden: a `ReconnectingConnection` whose first connect fails into a 60s backoff sees a
 * path change at t=1s — the change must cut the remaining backoff short and reconnect immediately
 * (the #222 backoff-vs-path-change race).
 *
 * The rung stays [NetworkState.LinkLocal] across the change, so `canRouteOffLink` never flips: what
 * cuts the backoff is unambiguously the **identity** edge, not a reachability edge. That separation is
 * only writable because the fixture scripts whole states (RFC_NETWORK_REACHABILITY §9.4).
 *
 * Replicates `ReconnectingConnectionNetworkTests.networkIdChangeCutsBackoffShort`, but driven
 * through the timeline engine — proving the same event model/interpreter generalizes from the
 * quiche driver tier to the transport tier.
 */
internal val networkFlapReconnect: SimFixture =
    simFixture("network-flap-reconnect") {
        at(1.seconds) net NetworkState.LinkLocal(NetworkId.KindOnly(NetworkKind.Cellular))
        runFor(1.seconds)
    }
