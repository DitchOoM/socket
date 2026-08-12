package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic

/**
 * Apple K/Native member of [QuicCertificateHashPinningTestSuite] — the Apple counterpart of
 * [JvmQuicCertificateHashPinningTests], [LinuxQuicCertificateHashPinningTests] and
 * `AndroidQuicCertificateHashPinningTests` (issue #296). Exercises the W3C `serverCertificateHashes`
 * leaf-hash pin end-to-end against a real loopback server, plus (where the platform enforces them) the
 * certificate constraints.
 *
 * **Where these five tests actually execute — measured, not assumed.**
 * Every test here needs a build-generated `pinned*` fixture, and those are reachable only from a cwd
 * containing `testcerts/`:
 *  - **macosArm64/macosX64** — cwd is the repo/module dir, so all five run for real. The three
 *    constraint-reject cases (`rejectsExpiredLeaf`, `rejectsOverlyLongValidityLeaf`,
 *    `rejectsNonP256KeyLeaf`) execute **only here** among the Apple targets.
 *  - **iOS simulator** — runs under `simctl spawn --standalone`, whose cwd has no `testcerts/`, so
 *    [AppleTestCerts.requireGenerated] throws [AppleTestCerts.MissingGeneratedFixture] and
 *    [wrapTestBody] converts that into a skip. **All five tests self-skip on iOS**, including
 *    `acceptsMatchingLeafHash` and `rejectsWrongLeafHash`. Do not read a green iOS tick here as
 *    end-to-end pinning coverage.
 *
 * Check it rather than trusting this comment — the skip is announced. After an
 * `iosSimulatorArm64Test` run, `build/test-results/iosSimulatorArm64Test/TEST-*.xml` carries five
 * `[QUIC-APPLE-FIXTURES] skipping: no testcerts/ in the simulator cwd` lines (K/N stdout reaches the XML
 * report even though this module's `testLogging` keeps it off the Gradle console). The same file shows
 * `[QUIC-PIN-CONSTRAINTS] running … serverCertificateConstraintSupport=Enforced` immediately before three
 * of them, which is the distinction exactly: the capability gate is **open** on iOS and the fixture
 * lookup is what stops the test, not a claim that iOS enforces less.
 *
 * That is a fixture-reachability limit, not a capability difference: [serverCertificateConstraintSupport]
 * reports `Enforced` for iOS **by construction**, because enforcement lives entirely in code iOS
 * compiles and links identically to macOS — `WithQuicConnection.apple.kt` (shared `appleMain`) passes
 * [parsePinnedLeafFieldsDer], and the parser plus [checkServerCertificatePinConstraints] are commonMain.
 * iOS's own automated coverage of that code is `PinnedLeafFieldsDerTests` (frozen positive/hostile
 * vectors, commonTest, runs on iosSimulatorArm64) and [AppleMozillaCaDerParseTest] (121 real Mozilla
 * roots, hermetic, runs on the simulator) — parser-level, not end-to-end.
 *
 * The constraint-reject cases are gated by the suite's own modeled capability —
 * `enforcesW3cConstraints()` reads [serverCertificateConstraintSupport] — and this member deliberately
 * does **not** override that gate. Wiring it up is what found issue #339: macOS advertised `Enforced`
 * while the Apple connect path still passed `parseLeafFields = null`, so on macOS those three cases
 * connected successfully instead of throwing. The gap is closed: the Apple connect path now passes the
 * shared [parsePinnedLeafFieldsDer] walk, and the three cases re-armed on macOS through that capability
 * with no edit here — the point of driving the gate off a modeled value rather than a hand-maintained
 * flag. A skip is announced (`[QUIC-PIN-CONSTRAINTS]` / `[QUIC-APPLE-FIXTURES]`), never a silent pass.
 *
 * The expected pin is read from the build-written `<fixture>.sha256` rather than hard-coded, so it can
 * never drift from a regenerated fixture — and it is computed by `java.security` in the build, an
 * implementation independent of the SHA-256 + DER walk under test here.
 */
class AppleQuicCertificateHashPinningTests : QuicCertificateHashPinningTestSuite() {
    override fun fixtureTlsConfig(name: String) =
        QuicTlsConfig(
            certChainPath = AppleTestCerts.requireGenerated("$name.crt"),
            privKeyPath = AppleTestCerts.requireGenerated("$name.key"),
        )

    override fun fixtureLeafHash(name: String): CertificateHash {
        val bytes = hexToBytes(AppleTestCerts.readText(AppleTestCerts.requireGenerated("$name.sha256")).trim())
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    /**
     * Skips only where the generated `pinned*` fixtures are physically unreachable (see
     * [AppleTestCerts.skippingWhenSimulatorLacksFixtures]) — every test in this suite needs one, so on an
     * Apple simulator that is the whole suite; on macOS a missing fixture is a hard failure.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) = AppleTestCerts.skippingWhenSimulatorLacksFixtures(block)

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }
}
