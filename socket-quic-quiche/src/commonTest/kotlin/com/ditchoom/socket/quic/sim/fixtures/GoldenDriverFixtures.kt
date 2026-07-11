package com.ditchoom.socket.quic.sim.fixtures

import com.ditchoom.socket.quic.sim.SimFixture
import com.ditchoom.socket.quic.sim.simFixture
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The hand-written W2 golden fixtures for the quiche-driver tier (RFC_DETERMINISTIC_SIMULATION.md
// §9 W2). Fixtures are pure input timelines; the driver configuration each one is meant to run
// under (keepalive interval, scripted quiche idle timer) is documented per fixture and applied by
// `GoldenFixtureTests`. The transport-layer golden (`network-flap-reconnect`) lives in the root
// module's `com.ditchoom.socket.transport.sim.fixtures` — it needs `ReconnectingConnection`.

/** Keepalive interval the keepalive/idle goldens run the driver with. */
internal val SIM_KEEPALIVE_INTERVAL: Duration = 10.seconds

/** Scripted quiche idle timer (`StubQuicheApi.connTimeout`) for the keepalive/idle goldens. */
internal val SIM_IDLE_TIMEOUT: Duration = 30.seconds

/**
 * Golden 1 — `keepalive-idle-survival`: three keepalive intervals pass with **no traffic at all**
 * (an empty event list — the timeline is pure clock advance). Run with keepalive = 10s and a
 * 30s idle timer armed to close on fire: the driver must PING at exactly t=10/20/30s, the idle
 * timer must never be handed to quiche, and the connection stays Established.
 */
internal val keepaliveIdleSurvival: SimFixture =
    simFixture("keepalive-idle-survival") {
        runFor(SIM_KEEPALIVE_INTERVAL * 3)
    }

/**
 * Golden 2 — `idle-timeout-close`: no keepalive, the 30s idle timer elapses with no traffic. The
 * driver must hand the fire to quiche and surface the close **typed** as `QuicError.IdleTimeout`
 * (never a string, never a clean-looking NoError).
 */
internal val idleTimeoutClose: SimFixture =
    simFixture("idle-timeout-close") {
        runFor(SIM_IDLE_TIMEOUT)
    }

/**
 * Golden 4 — `datagram-then-stale-path`: a datagram arrives, the network path changes (Wi-Fi →
 * cellular), and 5ms later another datagram arrives **on the old path** — the reconnect-race shape
 * (#222): a stale-path packet racing the path-change signal. The driver (clientMode = true, real
 * reader loop) must feed both packets to quiche and stay Established — the packet is data, not a
 * teardown signal; reacting to the path change belongs to the transport layer above.
 */
internal val datagramThenStalePath: SimFixture =
    simFixture("datagram-then-stale-path") {
        at(Duration.ZERO) datagramIn "0102030405060708"
        at(3.seconds) network NetworkId.KindOnly(NetworkKind.Cellular)
        at(3.seconds + 5.milliseconds) datagramIn "a1a2a3a4a5a6"
        runFor(4.seconds)
    }
