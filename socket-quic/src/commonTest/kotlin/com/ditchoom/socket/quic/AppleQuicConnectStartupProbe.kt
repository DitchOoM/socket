package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Iteration 2 of the macOS K/N crash investigation** (PR #54).
 *
 * Iter 1 (`--stacktrace --info` + `-Pkotlin.native.debug.exceptions=true`)
 * didn't surface anything because the crash is inside the K/N runtime
 * process — Gradle just sees its child die with exit code != 0 and reports
 * "Test running process exited unexpectedly. Current test:
 * harness_handshake_completesSuccessfully". No Kotlin stack trace, no
 * Network.framework error, no `harness OK` / `harness SKIP` marker because
 * the process died inside `withQuicConnection` before withHarness's
 * try/catch could reach its `println`.
 *
 * This probe isolates whether the crash is in `withQuicConnection`'s
 * startup path (`nw_helper_create_quic_connection` C call, cinterop
 * `List<String>` → `NSArray` bridge for ALPN, etc.) or in something
 * harness-specific (the cert pinning, `verifyPeer = false` path, the
 * established connection's handshake).
 *
 * It calls `withQuicConnection` against `192.0.2.1` (RFC-5737 TEST-NET-1,
 * documented as unreachable). The intent is for the connection to time
 * out after 1 second; we don't care about the failure mode, only that
 * the K/N process survives the call. We expect a `Throwable` (timeout
 * or refused). Anything caught is fine.
 *
 * - **Probe passes** → `withQuicConnection` startup is OK, the crash is
 *   harness-config-specific. Iter 3 narrows on ALPN list / verifyPeer.
 * - **Probe fails with "process exited unexpectedly"** → bug isolated to
 *   `withQuicConnection`'s startup itself. Iter 3 narrows on
 *   `nw_helper_create_quic_connection` parameters.
 *
 * Lives in `commonTest` so it runs on every platform target. On JVM /
 * Linux native, it should always pass (catches the timeout exception
 * cleanly). On macOS K/N, the test class name is alphabetically before
 * `QuicHarnessIntegrationTests`, so its result lands before the
 * harness-test failure in the test report.
 *
 * Delete this file once the macOS K/N crash is understood and either
 * fixed or @Ignored at the source — see TODO.md.
 */
class AppleQuicConnectStartupProbe {
    /**
     * Iter 2 probe — already known to crash. Renamed `z_*` so it runs
     * AFTER `a_*` and `b_*` below. K/N tests in a class run in alpha
     * order and the FIRST to crash terminates the whole test binary,
     * so on iter 3 (when this was `a1_*`) the other two probes never
     * got their chance. Running this last guarantees the baseline +
     * verify-peer-true probes have completed before the deterministic
     * crash kills the process.
     */
    @Test
    fun z_unreachableHost_verifyPeerFalse() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                try {
                    withQuicConnection(
                        "192.0.2.1",
                        14433,
                        QuicOptions(
                            alpnProtocols = listOf("h3"),
                            verifyPeer = false,
                            idleTimeout = 1.seconds,
                        ),
                        timeout = 1.seconds,
                    ) { }
                } catch (_: Throwable) {
                }
            }
        }

    /**
     * Iter 3 hypothesis: the always-accept verify_block installed when
     * `verifyPeer = false` triggers an abort under recent macOS TLS
     * hardening. nw_quic_helpers.h:58-63 calls
     * `sec_protocol_options_set_verify_block(...)` with a block that
     * unconditionally calls `complete(true)`. Apple has tightened cert
     * verification semantics across Network.framework; a permissive
     * verify block may now SIGABRT during `nw_parameters_create_quic`.
     *
     * Probe: same call but `verifyPeer = true`. The default verify path
     * is taken (no override block). Against `192.0.2.1` we still expect
     * a timeout/refused exception, but the K/N process should survive.
     *
     * - **Probe passes** → verify_block bypass is the culprit. Fix is
     *   to drop the override block on recent macOS, or use a different
     *   API (sec_protocol_options_append_tls_ciphersuite_group? or
     *   actual cert pinning to the harness's CA).
     * - **Probe still fails** → verify_block isn't the cause; iter 4
     *   probes a different parameter.
     */
    @Test
    fun b_unreachableHost_verifyPeerTrue() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                try {
                    withQuicConnection(
                        "192.0.2.1",
                        14433,
                        QuicOptions(
                            alpnProtocols = listOf("h3"),
                            verifyPeer = true,
                            idleTimeout = 1.seconds,
                        ),
                        timeout = 1.seconds,
                    ) { }
                } catch (_: Throwable) {
                }
            }
        }

    /**
     * Iter 3 hypothesis B: K/N coroutine machinery with
     * `withContext(Dispatchers.Default)` on macosArm64 is broken in some
     * way that interacts with the test runner. If even this trivial
     * probe — no QUIC call at all — crashes the K/N process, the bug is
     * way more fundamental than nw_quic_helpers.
     */
    @Test
    fun a_emptyWithContextDispatchersDefault_baseline() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                // Empty.
            }
        }
}
