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
    @Test
    fun withQuicConnectionAgainstUnreachableHostDoesNotCrashRuntime() =
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
                    ) {
                        // Never reached on an unreachable host. If the block
                        // somehow runs, that's a separate surprise — we still
                        // want the test to pass cleanly so the next iteration
                        // can use a different probe.
                    }
                } catch (_: Throwable) {
                    // Any throwable is fine — we're testing that the K/N
                    // process SURVIVES the call, not that the call succeeds.
                }
            }
        }
}
