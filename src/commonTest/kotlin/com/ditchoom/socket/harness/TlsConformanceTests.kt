package com.ditchoom.socket.harness

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SSLSocketException
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import com.ditchoom.socket.isHarnessAvailable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 2 conformance suite: drives the TLS-cert matrix served by
 * `test-harness/tls/`. Five cert scenarios × strict/lenient = ten
 * deterministic TLS assertions, no public internet involved.
 *
 *  - **valid** signed by harness-root  → 14443
 *  - **self-signed**                   → 14453
 *  - **expired** (backdated)           → 14463
 *  - **wrong-host** (SAN ≠ 127.0.0.1)  → 14473
 *  - **untrusted-root** (other CA)     → 14483
 *
 * Skipped silently when the harness isn't running (see [isHarnessAvailable]).
 *
 * **Phase-2 caveat — the *valid* path.** Proving that `tlsDefault()` succeeds
 * against the harness-root–signed cert requires the harness CA in each
 * platform's trust store (TESTING_STRATEGY.md §7.3 — chosen path is per-platform
 * CA injection in CI). Until that CI plumbing lands, the valid-path test uses
 * `tlsInsecure()` and is therefore only a smoke test that the TLS handshake
 * itself completes — not that default cert validation accepts the harness CA.
 * The four failure scenarios below *do* exercise default validation, because
 * they verify *rejection* — which doesn't depend on what is trusted.
 */
class TlsConformanceTests {
    // ── valid ─────────────────────────────────────────────────────────────────

    /**
     * TLS handshake completes against the valid (harness-root-signed) cert.
     * Uses [SocketOptions.tlsInsecure] until per-platform CA injection lands.
     */
    @Test
    fun tlsHarnessValidPassesWithInsecure() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsValidPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "TLS handshake against harness-valid should succeed")
            }
        }

    // ── self-signed ───────────────────────────────────────────────────────────

    @Test
    fun tlsHarnessSelfSignedFailsWithDefault() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            assertFailsWith<SSLSocketException> {
                ClientSocket.connect(
                    port = HarnessConfig.tlsSelfSignedPort,
                    hostname = harnessHost(),
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 5.seconds,
                ) { /* handshake must throw before lambda runs */ }
            }
        }

    @Test
    fun tlsHarnessSelfSignedPassesWithInsecure() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsSelfSignedPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "tlsInsecure() should accept the self-signed cert")
            }
        }

    // ── expired ───────────────────────────────────────────────────────────────

    @Test
    fun tlsHarnessExpiredFailsWithDefault() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            assertFailsWith<SSLSocketException> {
                ClientSocket.connect(
                    port = HarnessConfig.tlsExpiredPort,
                    hostname = harnessHost(),
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 5.seconds,
                ) { /* handshake must throw before lambda runs */ }
            }
        }

    @Test
    fun tlsHarnessExpiredPassesWithInsecure() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsExpiredPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "tlsInsecure() should accept the expired cert")
            }
        }

    // ── wrong-host ────────────────────────────────────────────────────────────

    @Test
    fun tlsHarnessWrongHostFailsWithDefault() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            // Cert SAN is `other.test`; we connect with `127.0.0.1` → hostname mismatch.
            assertFailsWith<SSLSocketException> {
                ClientSocket.connect(
                    port = HarnessConfig.tlsWrongHostPort,
                    hostname = harnessHost(),
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 5.seconds,
                ) { /* handshake must throw before lambda runs */ }
            }
        }

    @Test
    fun tlsHarnessWrongHostPassesWithHostnameVerifyOff() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            // Turning off only hostname verification still requires the cert to chain to a
            // trusted CA (which our harness CA isn't, on the platform side). Use the full
            // tlsInsecure() preset so this also covers the CA-trust gap until §7.3 lands.
            ClientSocket.connect(
                port = HarnessConfig.tlsWrongHostPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions(tcpNoDelay = true, tls = TlsConfig.INSECURE),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "tlsInsecure() should accept the wrong-host cert")
            }
        }

    // ── untrusted-root ────────────────────────────────────────────────────────

    @Test
    fun tlsHarnessUntrustedRootFailsWithDefault() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            assertFailsWith<SSLSocketException> {
                ClientSocket.connect(
                    port = HarnessConfig.tlsUntrustedPort,
                    hostname = harnessHost(),
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 5.seconds,
                ) { /* handshake must throw before lambda runs */ }
            }
        }

    @Test
    fun tlsHarnessUntrustedRootPassesWithInsecure() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            ClientSocket.connect(
                port = HarnessConfig.tlsUntrustedPort,
                hostname = harnessHost(),
                socketOptions = SocketOptions.tlsInsecure(),
                timeout = 5.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "tlsInsecure() should accept the untrusted-root cert")
            }
        }
}
