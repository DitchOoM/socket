@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
 *  - [captureFactory] — the W3 opt-in trace tap (RFC §5), **invoked once per [QuicheDriver]
 *    construction**. Default `{ null }` (zero cost). When it returns a recorder, that driver wraps
 *    its `UdpChannel`s in the recording decorator, mirrors state/pathState/close-error transitions
 *    into the trace, and polls path-stats on its timer wake. It is a *factory* (not one shared
 *    recorder) so a server [bind][com.ditchoom.socket.quic.QuicEngine.bind] mints a **fresh**
 *    recorder — and therefore a fresh `TraceSink` — for each accepted connection: one connection per
 *    sink, so each accepted connection's trace stays independently replayable (the v1 grammar carries
 *    no connection id). A client `connect` builds exactly one driver, so its factory returns the one
 *    recorder the connectivity tap also observes. Construct each recorder with THIS tuning's [clock]
 *    so all timestamps share one time source (RFC §5 "one clock").
 */
internal class QuicheDriverTuning(
    val driverContext: CoroutineContext = Dispatchers.Default,
    val clock: DriverClock = RealDriverClock,
    val random: Random = Random.Default,
    val wallClock: () -> Instant = { Clock.System.now() },
    val captureFactory: () -> TraceCapture = { TraceCapture.Off },
    /**
     * The client connection's resolved [com.ditchoom.socket.NetworkMonitor] observation, threaded to the
     * driver so it can latch [QuicConnection.networkAtClose] on the close transition. Default
     * [ConnectionNetworkObservation.Unobserved] — a server bind or a test double watches no network, and
     * says so with a value rather than with `null`.
     *
     * It rides this seam rather than a constructor argument on the connection wrapper because the value
     * must be frozen on the **driver loop**, at the same instant the connection publishes
     * [QuicConnectionState.Closed] — see `QuicheDriver.transitionToClosed`.
     */
    val networkObservation: ConnectionNetworkObservation = ConnectionNetworkObservation.Unobserved,
    /**
     * How long `QuicheDriver.flushOutgoing` waits for one `UdpChannel.send` before calling the path
     * stalled and closing it. Default [DEFAULT_SEND_STALL_BOUND].
     *
     * It is a seam for the same reason the others are: a simulation that wants to *reach* the stall
     * branch should not have to burn five seconds of the scheduler's budget to do it. Production
     * never sets it.
     */
    val sendStallBound: Duration = DEFAULT_SEND_STALL_BOUND,
)

/**
 * The default liveness backstop on one `UdpChannel.send` (see [QuicheDriverTuning.sendStallBound]).
 *
 * ## Why five seconds, and why it is not derived from any other timeout
 *
 * This bound measures **local platform responsiveness**, not anything about the network, so relating
 * it to a network timer would be a category error: a send hands a datagram to the kernel or to
 * Network.framework and is answered without waiting for the peer, so RTT, PTO and the peer's
 * transport parameters say nothing about how long one should take. NIO's send is synchronous;
 * io_uring and Network.framework answer in microseconds to milliseconds. Five seconds is roughly a
 * thousandfold headroom over any of them, which is the point — this can only fire on a platform that
 * has genuinely stopped answering, never on one that is merely loaded.
 *
 * The tempting derivation — scale it from the connection's idle timeout — is the wrong one twice
 * over. The idle timer is precisely what an unbounded send prevents from ever running, so a bound
 * expressed in terms of it inherits the failure it exists to break; and a connection configured with
 * a short idle timeout would get a bound tight enough to reap healthy paths under load.
 *
 * The cost of a false stall is bounded and recoverable — one closed socket, one reconnect — which is
 * what lets the value be generous rather than tuned.
 */
internal val DEFAULT_SEND_STALL_BOUND = 5.seconds
