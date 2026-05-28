package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Macos K/N crash investigation probes** (PR #54).
 *
 * Iter 1 (--stacktrace --info) didn't surface a native stack — Gradle
 * just sees its K/N child die. Iter 2 narrowed to "withQuicConnection
 * startup itself crashes, not harness-specific" by hitting an
 * unreachable RFC-5737 host. Iter 3 added two more probes but they
 * never ran: K/N stops the test binary on the first crash, and tests
 * execute in **source declaration order** (NOT alphabetical, as iter 3
 * assumed) — so whichever crashing test is declared first kills the
 * binary before later probes get their chance.
 *
 * Iter 4 confirmed source-declaration ordering: the `z_*` probe (still
 * declared first then) ran alone and crashed. Iter 5 reorders the
 * source so the baselines (`a_*`, `b_*`) are declared BEFORE the
 * known-crashing `z_*` — that guarantees they execute.
 *
 * Lives in commonTest so it also runs on JVM + Linux native, where
 * everything should pass (timeout exceptions are normal). Delete once
 * the macOS K/N crash is understood and either fixed or @Ignored at
 * the source — see TODO.md.
 */
class AppleQuicConnectStartupProbe {
    /**
     * Hypothesis B: K/N coroutine machinery with `withContext(Dispatchers.Default)`
     * on macosArm64 is broken in some way that interacts with the test
     * runner. If even this trivial probe — no QUIC call at all —
     * crashes the K/N process, the bug is way more fundamental than
     * nw_quic_helpers.
     */
    @Test
    fun a_emptyWithContextDispatchersDefault_baseline() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                // Empty.
            }
        }

    /**
     * Hypothesis A: the always-accept verify_block installed when
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
     * - **Probe still fails** → verify_block isn't the cause; iter 6
     *   probes a different parameter (ALPN bridge, port string,
     *   nw_parameters_create_quic block contents).
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
     * Known crash since iter 2. Kept LAST in source order so it doesn't
     * pre-empt the baseline + verifyPeer=true probes. K/N test runner
     * stops on the first crash; declaring this here means it runs
     * after the two informative ones above.
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
}
