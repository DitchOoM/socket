@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The quiche backend's determinism seams, bundled so the internal connection/server factories can
 * thread all of them to every [QuicheDriver] construction site in one hop — the W1 seam of
 * RFC_DETERMINISTIC_SIMULATION.md (§3.1): make the driver fully virtual-time-drivable and
 * entropy-seedable without touching `:socket-quic`'s public API.
 *
 * Production code never builds one — every default is byte-identical to the pre-seam behaviour —
 * while the simulation harness supplies a tuning whose members put the driver under test control:
 *
 *  - [driverContext] — context the driver's control loop and per-path UDP reader loops launch in.
 *    Default [Dispatchers.Default] (the pre-seam hardwired dispatcher). A test passes
 *    [kotlin.coroutines.EmptyCoroutineContext] so the loops inherit the caller's virtual-time test
 *    dispatcher and [DriverClock.armTimeout] wakes land on the `kotlinx-coroutines-test` scheduler.
 *  - [clock] — the driver's keepalive/idle timing seam ([DriverClock]). Default [RealDriverClock];
 *    tests inject `ManualDriverClock` to hand-fire timers race-free.
 *  - [random] — entropy for connection IDs ([generateScid]) and stateless-reset tokens. Default
 *    [Random.Default]; one seeded instance makes every generated CID/token reproducible.
 *  - [wallClock] — "now" for the W3C `serverCertificateHashes` validity-window check
 *    ([verifyServerCertificateHashes]/[checkServerCertificatePinConstraints]). Default
 *    `Clock.System.now()`; a fixture replay pins it to the recorded capture time.
 */
internal class QuicheDriverTuning(
    val driverContext: CoroutineContext = Dispatchers.Default,
    val clock: DriverClock = RealDriverClock,
    val random: Random = Random.Default,
    val wallClock: () -> Instant = { Clock.System.now() },
)
