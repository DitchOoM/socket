package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **W3C `serverCertificateHashes` leaf-hash pinning + certificate-constraint** test suite (Phase 4,
 * Option 1). Exercises the full quiche-backend verifier end-to-end against a real loopback server:
 * handshake (with `verify_peer` off under the default [CertificateHashVerification.HashOnly]), then the
 * post-handshake `quiche_conn_peer_cert` read → SHA-256 → compare → (once matched) the W3C constraint
 * check (validity ≤ 14 days, currently valid, ECDSA P-256).
 *
 * Same 3-tier shape as the other suites: this commonMain abstract drives the tests against the build's
 * generated fixture matrix; per-platform subclasses resolve a fixture's TLS config + the expected pin
 * (the SHA-256 of that cert's leaf DER — computed independently of the impl under test: JVM via
 * `java.security`, Linux/Apple via the build-written `<fixture>.sha256` file).
 *
 * Fixtures (see the `generatePinnedW3cCerts` build task): `pinned` is the compliant accept cert (EC
 * P-256, 13-day); each violator isolates one constraint branch — `pinned-expired` (NotTemporallyValid),
 * `pinned-toolong` (ValidityPeriodTooLong), `pinned-rsa` (UnsupportedPublicKey). The day-precision
 * boundaries themselves are covered deterministically in `ServerCertificatePinConstraintsTests`.
 */
abstract class QuicCertificateHashPinningTestSuite {
    /** TLS identity for a named fixture (`pinned`, `pinned-expired`, `pinned-toolong`, `pinned-rsa`). */
    abstract fun fixtureTlsConfig(name: String): QuicTlsConfig

    /** The expected pin: SHA-256 of the named fixture's leaf certificate DER, as a [CertificateHash]. */
    abstract fun fixtureLeafHash(name: String): CertificateHash

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * Whether this platform enforces the W3C certificate constraints — driven by the modeled
     * [serverCertificateConstraintSupport] capability rather than a hand-maintained per-backend flag. The
     * constraint-reject tests are gated open on a [ServerCertificateConstraintSupport.Enforced] platform
     * and skipped on [ServerCertificateConstraintSupport.NoQuicEngine] or on the currently unproduced
     * [ServerCertificateConstraintSupport.LeafHashOnly]. The accept + wrong-hash tests run regardless.
     *
     * Where that actually resolves today, per concrete member (there are four: JVM, Android, Linux,
     * Apple):
     *  - JVM/Android/Linux/macOS — `Enforced`, and the three cases execute end-to-end.
     *  - **iOS simulator** — `Enforced` too, so this gate opens, but every test in the Apple member
     *    still self-skips one step later for want of the build-generated `pinned*` fixtures, which the
     *    simulator's cwd cannot reach. See `AppleQuicCertificateHashPinningTests`; a green iOS tick here
     *    is not end-to-end evidence.
     *  - tvOS/watchOS and JS/wasmJs — `NoQuicEngine`, and no member of this suite is compiled for them
     *    at all (`:socket-quic-quiche` registers no such target), so this branch is a statement about the
     *    capability, not a run that happens.
     *
     * Returning `false` here does not skip — it **fails**. Every member of this suite is compiled only
     * for a backend that must enforce, so a `false` is a capability regression, and a regression that
     * reports green is precisely how #339 survived. See [runConstraintRejectTest].
     */
    protected open fun enforcesW3cConstraints(): Boolean = serverCertificateConstraintSupport is ServerCertificateConstraintSupport.Enforced

    /**
     * The concrete subclass's name (`JvmQuicCertificateHashPinningTests`, `AppleQuic…`, …) — the closest
     * thing this commonMain suite has to a platform label, used in the `[QUIC-PIN-CONSTRAINTS]` marker.
     * A property, not an inline `this::class`: inside [runQuicTest] the receiver is a `CoroutineScope`.
     */
    private val memberName: String?
        get() = this::class.simpleName

    private fun options() = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    /** A pin that does NOT match the server's leaf (32 zero bytes) — must be rejected. */
    private fun wrongHash(): CertificateHash {
        val buf = BufferFactory.deterministic().allocate(32)
        repeat(32) { buf.writeByte(0) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    /** Pinning the server's actual leaf-cert hash lets the connection establish (the pin is the trust check). */
    @Test
    fun acceptsMatchingLeafHash() =
        runQuicTest {
            wrapTestBody {
                val opts = options()
                withQuicServer(port = 0, tlsConfig = fixtureTlsConfig("pinned"), quicOptions = opts) {
                    val handlerRan = CompletableDeferred<Unit>()
                    val serverJob = launch { connections { handlerRan.complete(Unit) } }
                    try {
                        val pinned = opts.copy(serverCertificateHashes = listOf(fixtureLeafHash("pinned")))
                        // No throw == the leaf-hash pin verified AND the W3C constraints passed.
                        withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds.scaled) {}
                        handlerRan.await()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * Pinning a non-matching hash must reject the connection with a [CertificateHashPinningException]
     * whose [CertificateHashPinningFailure.HashMismatch] carries the leaf the server actually presented.
     * Every backend throws the identical type (quiche post-handshake; Apple/NW from the verify_block),
     * and `HashMismatch` (vs `NoPeerCertificate`) also proves the leaf DER was read at runtime.
     */
    @Test
    fun rejectsWrongLeafHash() =
        runQuicTest {
            wrapTestBody {
                val opts = options()
                withQuicServer(port = 0, tlsConfig = fixtureTlsConfig("pinned"), quicOptions = opts) {
                    val serverJob = launch { runCatching { connections {} } }
                    try {
                        val pinned = opts.copy(serverCertificateHashes = listOf(wrongHash()))
                        val ex =
                            assertFailsWith<CertificateHashPinningException> {
                                withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds.scaled) {}
                            }
                        val failure = ex.failure
                        assertTrue(
                            failure is CertificateHashPinningFailure.HashMismatch,
                            "expected HashMismatch, got: $failure",
                        )
                        assertTrue(
                            failure.computedLeafHash.startsWith("sha-256:"),
                            "expected an algorithm-prefixed computed hash, got: ${failure.computedLeafHash}",
                        )
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /** A pinned leaf whose hash matches but whose validity window is in the past — NotTemporallyValid. */
    @Test
    fun rejectsExpiredLeaf() =
        runConstraintRejectTest("pinned-expired") { failure ->
            assertTrue(
                failure is CertificateHashPinningFailure.NotTemporallyValid,
                "expected NotTemporallyValid, got: $failure",
            )
        }

    /** A pinned leaf whose validity period exceeds 14 days — ValidityPeriodTooLong. */
    @Test
    fun rejectsOverlyLongValidityLeaf() =
        runConstraintRejectTest("pinned-toolong") { failure ->
            assertTrue(
                failure is CertificateHashPinningFailure.ValidityPeriodTooLong,
                "expected ValidityPeriodTooLong, got: $failure",
            )
        }

    /** A pinned leaf with a non-ECDSA-P-256 (RSA) key — UnsupportedPublicKey. */
    @Test
    fun rejectsNonP256KeyLeaf() =
        runConstraintRejectTest("pinned-rsa") { failure ->
            assertTrue(
                failure is CertificateHashPinningFailure.UnsupportedPublicKey,
                "expected UnsupportedPublicKey, got: $failure",
            )
        }

    /**
     * Drive a constraint-violation fixture end-to-end: the server presents [fixture], the client pins
     * that leaf's real hash (so the hash matches and the W3C constraint check is what rejects), and
     * [assertFailure] checks the structured failure. Skips on backends not enforcing constraints.
     *
     * The skip is **loud**. `kotlin.test` has no common skip primitive, so an early return is
     * indistinguishable from a pass on the tick alone — and a silently-disarmed security test is exactly
     * how issue #339 stayed hidden (macOS advertised `Enforced` while nothing enforced it). Following the
     * repo's existing marker idiom (`[QUIC-SIM-HARNESS]`, `[QUIC-APPLE-FIXTURES]`), the decision is
     * printed with the concrete suite member and the capability value that caused it, so grepping
     * `QUIC-PIN-CONSTRAINTS` over a run tells you which platforms actually armed these three cases.
     *
     * On every platform the line lands in the JUnit XML under
     * `build/test-results/<target>Test/TEST-*.xml` (`<system-out>`), including Kotlin/Native. The module
     * that runs these members, `:socket-quic-quiche`, configures `testLogging` without
     * `showStandardStreams`, so stdout stays off the Gradle console — but not out of the XML report.
     * Verified on both: the jvmTest XML and `macosArm64Test`'s carry the marker lines.
     */
    private fun runConstraintRejectTest(
        fixture: String,
        assertFailure: (CertificateHashPinningFailure) -> Unit,
    ) = runQuicTest {
        wrapTestBody {
            if (!enforcesW3cConstraints()) {
                // NOT a skip. A marker in `<system-out>` was not enough: with the capability forced to
                // LeafHashOnly the three cases still reported TEST SUCCESS and the XML still said
                // `skipped="0" failures="0"`, so nothing a CI gate reads could tell the difference — the
                // same green-while-disarmed shape as #339 itself. All four members (JVM, Android, Linux,
                // Apple) compile only for backends that must enforce, and none overrides this gate, so a
                // `false` here is a regression and has to be red.
                fail(
                    "[QUIC-PIN-CONSTRAINTS] DISARMED '$fixture' in $memberName: " +
                        "enforcesW3cConstraints() returned false " +
                        "(serverCertificateConstraintSupport=$serverCertificateConstraintSupport). " +
                        "Every member of this suite runs on a backend that must enforce the W3C constraints, so this " +
                        "is a capability regression, not a skip. A backend that genuinely cannot enforce them must be " +
                        "removed from this suite deliberately — which is a reviewable change — rather than silently " +
                        "disarming these three security tests.",
                )
            }
            println(
                "[QUIC-PIN-CONSTRAINTS] running '$fixture' in $memberName: " +
                    "serverCertificateConstraintSupport=$serverCertificateConstraintSupport",
            )
            val opts = options()
            // The `pinned-rsa` fixture deliberately presents an RSA leaf. On Apple that trips the
            // Network.framework server anti-amplification guard (RSA flight can't be delivered to a
            // non-Apple client). This suite is NW↔NW on Apple (the client is also Network.framework, which
            // the bug doesn't affect), and it tests *pinning*, not interop — so bypass the guard for the
            // server. The flag is ignored on every non-Apple backend and for the EC fixtures.
            val serverOpts = opts.copy(appleAllowOversizedServerCert = true)
            withQuicServer(port = 0, tlsConfig = fixtureTlsConfig(fixture), quicOptions = serverOpts) {
                val serverJob = launch { runCatching { connections {} } }
                try {
                    val pinned = opts.copy(serverCertificateHashes = listOf(fixtureLeafHash(fixture)))
                    val ex =
                        assertFailsWith<CertificateHashPinningException> {
                            withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds.scaled) {}
                        }
                    assertFailure(ex.failure)
                } finally {
                    serverJob.cancel()
                }
            }
        }
    }
}
