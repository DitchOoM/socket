package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC test runner with a 15-second wall-clock timeout.
 *
 * Replaces the pre-v2 `kotlinx.coroutines.runBlocking` wrapper, which
 * doesn't exist on Kotlin/JS (or WasmJs) — the test class is in
 * `commonTest` and is supposed to run on every target to assert
 * common-API behavioral parity, but the old `runBlocking` fell over at
 * compile time on JS/WasmJs.
 *
 * Uses [runTest] so the result type is [TestResult] — a typealias for
 * `Unit` on JVM / K/N and `Promise<Unit>` on JS / WasmJs, giving the
 * test framework the right shape on every platform. The body runs on
 * `Dispatchers.Default` (via `withContext`) so real I/O and real
 * timing work — the reactive-driver tests rely on cooperative yield
 * and actual channel pumping and don't want virtual-time
 * fast-forwarding to skip over `withTimeout` budgets.
 */
fun runQuicTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest(timeout = 30.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(15.seconds) { block() }
        }
    }

/**
 * Reactive replacement for `delay(N)` followed by an assertion: yields the dispatcher
 * until [predicate] holds, with [timeout] as a wall-clock backstop. Converges within
 * one scheduler tick once the awaited event fires — no fixed wait, no polling on
 * wall-clock time, no spinning the CPU.
 *
 * Use this anywhere a test was written like `delay(500) // let X propagate; assertEquals(…)`.
 * The [reason] is surfaced in the [TimeoutCancellationException] message when the
 * predicate never holds, making timeouts diagnose themselves.
 */
suspend fun awaitUntil(
    timeout: Duration,
    reason: String,
    predicate: () -> Boolean,
) {
    try {
        withTimeout(timeout) {
            while (!predicate()) yield()
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("awaitUntil($timeout) timed out: $reason")
    }
}

/**
 * True when the current runtime is a Kotlin/Native Apple target
 * (macosArm64, macosX64, iosArm64, iosSimulatorArm64, iosX64,
 * tvosArm64, tvosSimulatorArm64, tvosX64, watchosArm64,
 * watchosSimulatorArm64, watchosX64).
 *
 * Used by [QuicHarnessIntegrationTests] to skip the harness suite on Apple
 * K/N targets. The handshake SIGTRAP that previously blocked Apple QUIC is
 * fixed (nw_quic_copy_sec_protocol_options, PR #60) — the client now runs the
 * full suite. The remaining gap is cert *acceptance*: NW's QUIC TLS rejects the
 * private-CA, non-CT-logged harness cert with errSSLBadCert (-9808), and the
 * verify_block that would override trust SIGABRTs under macOS hardening
 * (PR #54). Public CT-logged certs are unaffected. See the withHarness comment.
 *
 * (Earlier docstrings referenced an `AppleQuicConnectStartupProbe` as the Apple
 * smoke test — it never existed in the repo. Tracked under TODO.md "macOS
 * harness coverage"; a real startup probe + the -9808 fix are the follow-ups.)
 */
internal expect fun isAppleKNative(): Boolean

/**
 * True when the QUIC harness suite must be skipped because it's running on an Apple
 * simulator that can't exercise Network.framework QUIC.
 *
 * NOT a platform limitation — QUIC works fine on the iOS Simulator. The blocker is
 * the Kotlin/Native test runner: KGP launches simulator tests with
 * `simctl spawn --standalone`, which runs the test binary OUTSIDE the simulator's
 * `launchd_sim` service context. Network.framework's QUIC datapath needs those
 * network daemons (nehelper / nw services), so under `--standalone` an
 * `nw_parameters_create_quic` connection hangs in `preparing` and never reaches
 * `ready`. Raw-socket TCP doesn't need them, which is why the rest of the Apple
 * suite passes and hides this.
 *
 * Proven empirically (2026-06-02, iOS 26.5 build 23F77, iPhone 17 Pro sim), all
 * against the SAME device + OS:
 *   - our K/N `test.kexe` via `simctl spawn --standalone`     → public QUIC TIMEOUT (even at 45s)
 *   - our K/N `test.kexe` via `simctl spawn` (no --standalone) → public QUIC OK in 87ms
 *   - a normally-launched Swift NW QUIC app                    → READY in 36-47ms
 *   - physical iPhone (iOS 26.5)                               → READY in 42-50ms
 *
 * The fix: run the iOS-simulator test task with `standalone = false` against a
 * pre-booted simulator. The Gradle build flips that on (and sets `QUIC_SIM_BOOTED=1`)
 * only when `-PiosSimulatorDevice=<udid>` is supplied — CI does this after
 * `simctl boot`. In that booted mode this returns false on the iOS simulator and the
 * harness runs. Without it (local `./gradlew check`, which keeps KGP's auto-boot +
 * `--standalone`) it returns true and the harness self-skips rather than hanging.
 * tvOS/watchOS simulators always skip (out of scope for now). macOS K/N (no
 * simulator, real network stack) returns false and validates the QUIC client.
 */
internal expect fun shouldSkipQuicHarnessOnSimulator(): Boolean
