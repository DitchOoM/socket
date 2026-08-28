package com.ditchoom.socket

import com.ditchoom.socket.harness.HarnessConfig
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.reflect.KClass
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Returns true if running in an iOS/tvOS/watchOS Simulator environment.
 * iOS Simulators in CI often have restricted network access to external hosts.
 */
expect fun isRunningInSimulator(): Boolean

/**
 * `true` when the current process is a JVM running on Windows.
 *
 * Used as a coarse skip-guard for tests whose root cause sits in the JVM
 * NIO2 layer on Windows (different exception-mapping semantics than POSIX
 * — see JvmExceptionMapping.kt). The contract those tests cover is still
 * exercised on Linux/macOS JVM, K/Native, JS, and Apple targets; the
 * Windows skip is a TODO toward proper JVM/Windows exception mapping.
 *
 * Returns `false` on every non-JVM target (JS, K/Native, Apple, Wasm).
 */
internal expect fun isWindowsJvm(): Boolean

/**
 * Whether `NonDrainingPeer` can reliably create write back-pressure on this platform (the
 * write-timeout contract, RFC_WRITE_TIMEOUT_CONTRACT.md).
 *
 * `false` on Node/JS (and browser/Wasm): a Node `net.Socket` switches to flowing mode the moment a
 * `'data'` listener is attached — which our `ServerSocket` does on accept — so the OS receive buffer is
 * always drained into an unbounded channel and the client never back-pressures. Node also has no
 * OS-level writer suspension, only cooperative `highWaterMark` `'drain'`. The Node write path is covered
 * separately by a raw-`net`, paused-peer test in `jsTest` (`NodeWriteBackpressureTests`). `true`
 * everywhere the accepted socket is pull-based (JVM, K/Native, Apple).
 */
expect fun nonDrainingPeerIsReliable(): Boolean

/**
 * Platform-specific return type for test functions.
 * On JVM/K/N: Unit (required by K/N test framework).
 * On JS: Any (allows returning Promise for mocha async test tracking).
 */
expect class TestRunResult

/**
 * Runs a test with real-time timeout (no virtual time skipping).
 * Platform-specific: uses runBlocking on JVM/Native, GlobalScope.promise on JS.
 */
internal expect fun runTestNoTimeSkipping(
    count: Int = 1,
    timeout: Duration = 30.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult

/**
 * Skip the block if running in a simulator environment (e.g., iOS Simulator in CI).
 */
internal inline fun skipOnSimulator(block: () -> Unit) {
    if (!isRunningInSimulator()) {
        block()
    }
}

/**
 * Wait for a mutex to be unlocked with a timeout.
 * This prevents tests from hanging indefinitely if the unlock never happens.
 */
internal suspend fun Mutex.lockWithTimeout(
    timeout: Duration = 10.seconds,
    owner: Any? = null,
) {
    withTimeout(timeout) {
        lock(owner)
    }
}

// ──────────────────────────────────────────────────────────────────────
// Harness reachability (see test-harness/ and TESTING_STRATEGY.md §3a)
// ──────────────────────────────────────────────────────────────────────

/**
 * Host the local test harness is reachable on for the current platform.
 *
 * JVM / K-Native / Apple: returns `HarnessConfig.host` (typically `127.0.0.1`)
 * — harness and test share the runner.
 *
 * Browser (JS/wasmJs): same default. Browser targets do not exercise the
 * socket harness today (no raw-socket surface), so this value is consumed
 * only by potential future WebSocket-shape tests; switch the browser actual
 * to `window.location.hostname` if that ever changes.
 */
internal expect fun harnessHost(): String

/**
 * `true` when the harness TCP echo endpoint is reachable from this process.
 *
 * Probes `HarnessConfig.echoPort` on [harnessHost] with a 500 ms budget.
 * Used by harness-backed tests so the suite stays green when the local
 * stack isn't up (e.g. local dev without Docker, or a CI runner where
 * `harnessUp` no-op'd because the host isn't supported).
 *
 * Returns `false` immediately on browser/wasmJs (`WEBSOCKETS_ONLY`).
 */
internal suspend fun isHarnessAvailable(): Boolean {
    if (!networkCapabilities().transports.contains(TransportKind.TCP)) return false
    return try {
        ClientSocket.connect(
            port = HarnessConfig.echoPort,
            hostname = harnessHost(),
            config = TransportConfig(connectTimeout = 500.milliseconds),
        ) { /* immediate close — we just needed to know the listener is alive */ }
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * What the harness `netem-blackhole` endpoint did with one SYN.
 *
 * Three outcomes, and none may be folded into another. The `isNetemAvailable(): Boolean` this
 * replaces mapped every throwable to `false`, so "the harness is down" and "the harness is up but
 * the blackhole is not blackholing" were the same silent early return — which is how the harness
 * ran for months with a `netem loss 100%` qdisc that also dropped ARP replies: no SYN ever left the
 * client host, Linux answered `connect()` with EHOSTUNREACH once the neighbour entry failed, and the
 * root tests either swallowed that as a `SocketException` or timed out inside the ~3 s ARP retry
 * window and passed for the wrong reason (PR #501).
 */
internal sealed interface BlackholeProbe {
    /** The SYN went out and nothing came back inside the budget: the egress drop is in place. */
    data object Blackholing : BlackholeProbe

    /** The listener answered — a SYN-ACK arrived, so the drop filter is not installed. */
    data object Answered : BlackholeProbe

    /** `connect()` failed outright (no route, refused, …): the endpoint is not reachable at L2/L3. */
    data class Unreachable(
        val cause: Throwable,
    ) : BlackholeProbe
}

/**
 * Longer than Linux's ARP retry window (`mcast_solicit` × `retrans_time` ≈ 3 s), on purpose: a
 * budget shorter than that cannot tell "address resolution still pending" from "SYN-ACK dropped",
 * and would report [BlackholeProbe.Blackholing] for a blackhole that has stopped answering ARP.
 * Paid once per process — see [requireNetemBlackhole].
 */
private val BLACKHOLE_PROBE_BUDGET = 5.seconds

/**
 * Send one SYN to `HarnessConfig.netemBlackholeHost:netemBlackholePort` and classify what happened.
 * A deadline expiry is a timeout whichever type the platform reports it as: the JVM surfaces
 * kotlinx's [TimeoutCancellationException] from its `withTimeout`, the other backends map it to
 * [SocketTimeoutException] (`TimeoutContext.Connect`).
 */
internal suspend fun probeNetemBlackhole(): BlackholeProbe =
    try {
        ClientSocket.connect(
            port = HarnessConfig.netemBlackholePort,
            hostname = HarnessConfig.netemBlackholeHost,
            config = TransportConfig(connectTimeout = BLACKHOLE_PROBE_BUDGET),
        ) { /* unreachable when the blackhole is working — the SYN-ACK is dropped */ }
        BlackholeProbe.Answered
    } catch (_: TimeoutCancellationException) {
        BlackholeProbe.Blackholing
    } catch (_: SocketTimeoutException) {
        BlackholeProbe.Blackholing
    } catch (t: Throwable) {
        BlackholeProbe.Unreachable(t)
    }

/** The probe runs once per test process; every netem-backed test in it reads the same verdict. */
private sealed interface BlackholeProbeState {
    data object Pending : BlackholeProbeState

    data class Done(
        val probe: BlackholeProbe,
    ) : BlackholeProbeState
}

private val blackholeProbeLock = Mutex()
private var blackholeProbeState: BlackholeProbeState = BlackholeProbeState.Pending

private suspend fun cachedBlackholeProbe(): BlackholeProbe =
    blackholeProbeLock.withLock {
        when (val state = blackholeProbeState) {
            is BlackholeProbeState.Done -> state.probe
            BlackholeProbeState.Pending -> probeNetemBlackhole().also { blackholeProbeState = BlackholeProbeState.Done(it) }
        }
    }

/**
 * Gate for the netem-backed tests. `true` means run the body. Otherwise the caller returns at once,
 * because this has already either recorded a loud skip or failed the test:
 *
 *  - **Harness absent** (the echo probe answers nothing, or this platform has no TCP at all) →
 *    `[TEST-SKIPPED]` with [SkipGate.HostCannotProvideIt]: a CI macOS runner has no Docker and no
 *    lane setting can give it one, so this must not go red under `SOCKET_REQUIRE_ALL_TESTS=1`.
 *  - **Harness up, blackhole answered the SYN** → `fail()`, everywhere: a completed handshake is a
 *    definite verdict that the egress drop filter is not installed.
 *  - **Harness up, blackhole unreachable** (EHOSTUNREACH, ECONNREFUSED, …) → `[TEST-SKIPPED]` with
 *    [SkipGate.LaneMustRunEveryTest], which is a **failure** on the lane that provisions the bridge
 *    route (Linux, `SOCKET_REQUIRE_ALL_TESTS=1`) and a loud, counted skip elsewhere. Not an
 *    unconditional failure because a developer Mac running Docker Desktop has the harness up and no
 *    route to the bridge subnet at all; whether its LAN gateway turns a private-range SYN into a
 *    timeout or an ICMP unreachable is not a property of this repository.
 */
internal suspend fun requireNetemBlackhole(site: KClass<*>): Boolean {
    val endpoint = "${HarnessConfig.netemBlackholeHost}:${HarnessConfig.netemBlackholePort}"
    if (!isHarnessAvailable()) {
        recordSkip(
            site,
            SkipReason.HarnessUnreachableFromDevice(
                "docker harness echo at ${harnessHost()}:${HarnessConfig.echoPort} answered nothing from this " +
                    "process, so the netem-blackhole at $endpoint was not probed — stack not up on this host",
            ),
            gate = SkipGate.HostCannotProvideIt("docker test harness"),
        )
        return false
    }
    return when (val probe = cachedBlackholeProbe()) {
        BlackholeProbe.Blackholing -> true
        BlackholeProbe.Answered ->
            fail(
                "harness is up but netem-blackhole $endpoint completed the TCP handshake — its egress " +
                    "drop filter is not installed (test-harness/docker-compose.yml, service netem-blackhole)",
            )
        is BlackholeProbe.Unreachable -> {
            recordSkip(
                site,
                SkipReason.HarnessUnreachableFromDevice(
                    "harness is up but netem-blackhole $endpoint failed outright within " +
                        "$BLACKHOLE_PROBE_BUDGET: ${probe.cause} — on a Linux host that is an ARP/route " +
                        "failure (the blackhole stopped answering ARP), not a blackhole",
                ),
                gate = SkipGate.LaneMustRunEveryTest,
            )
            false
        }
    }
}
