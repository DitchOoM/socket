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
 * The constraint-reject cases are gated by the suite's own modeled capability —
 * `enforcesW3cConstraints()` reads [serverCertificateConstraintSupport] — and this member deliberately
 * does **not** override that gate. Wiring it up is what found issue #339: macOS advertised `Enforced`
 * while the Apple connect path passes `parseLeafFields = null`, so `rejectsExpiredLeaf`,
 * `rejectsOverlyLongValidityLeaf` and `rejectsNonP256KeyLeaf` all connected successfully instead of
 * throwing. The capability now reports the truth (`LeafHashOnly` on every Apple target), so those three
 * skip through the existing mechanism rather than an ad-hoc ignore — and they re-arm by themselves the
 * moment #339 lands an Apple leaf-field parser and flips the capability back. `acceptsMatchingLeafHash`
 * and `rejectsWrongLeafHash` run on every Apple target and pass, which is what proves the leaf-hash pin
 * — the actual trust check — really is enforced here.
 *
 * The fixture matrix (`pinned`, `pinned-expired`, `pinned-toolong`, `pinned-rsa`) is minted fresh by the
 * `generatePinnedW3cCerts` task, so — like `localhost.*` — it cannot be embedded and is reachable only
 * from a cwd with `testcerts/`. [wrapTestBody] therefore skips on an Apple simulator and only there; a
 * missing fixture on macOS stays a hard failure (see [AppleTestCerts.requireGenerated]).
 *
 * The expected pin is read from the build-written `<fixture>.sha256` rather than hard-coded, so it can
 * never drift from a regenerated fixture — and it is computed by `java.security` in the build, an
 * implementation independent of the BoringSSL-cinterop verifier under test.
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
