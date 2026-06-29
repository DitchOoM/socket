package com.ditchoom.socket.quic

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Negative guard for the W3C `serverCertificateHashes` constraint fixtures the pinning tests use
 * (`:socket-quic-quiche:generatePinnedW3cCerts`). The constraint verifier accepts only an EC P-256 leaf
 * with ≤ 14-day, currently-valid validity, so the fixtures must keep their *intended* shapes or the
 * accept/reject e2e tests would silently test the wrong thing. A failure here means the keytool args in
 * `generatePinnedW3cCerts` drifted — fix them there.
 *
 * Runs JVM-only (full X.509 parsing); the fixtures are shared, so guarding them once covers the
 * Linux/Apple/Android consumers too.
 */
class PinnedCertFixtureGuardTest {
    private fun load(name: String): X509Certificate {
        val stream =
            this::class.java.classLoader.getResourceAsStream("certs/$name")
                ?: error("certs/$name not on the test classpath — did :socket-quic-quiche:generatePinnedW3cCerts run?")
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }

    private val hint = "Fix the keytool args in the generatePinnedW3cCerts task (socket-quic-quiche/build.gradle.kts)."

    private fun validityDays(cert: X509Certificate) = (cert.notAfter.time - cert.notBefore.time) / (24L * 60 * 60 * 1000)

    private fun isEcP256(cert: X509Certificate): Boolean {
        val key = cert.publicKey
        return key is ECPublicKey && key.params.curve.field.fieldSize == 256
    }

    @Test
    fun pinnedIsCompliantEcP256ShortLivedCurrentlyValid() {
        val cert = load("pinned.crt")
        assertTrue(isEcP256(cert), "pinned.crt is not EC P-256 (got ${cert.publicKey.algorithm}). $hint")
        assertTrue(validityDays(cert) <= 14, "pinned.crt validity is ${validityDays(cert)} days, must be ≤ 14. $hint")
        val nowMs = System.currentTimeMillis()
        assertTrue(
            cert.notBefore.time <= nowMs && nowMs <= cert.notAfter.time,
            "pinned.crt is not currently valid (${cert.notBefore}..${cert.notAfter}) — stale fixture? $hint",
        )
        val eku = cert.extendedKeyUsage ?: emptyList()
        assertTrue("1.3.6.1.5.5.7.3.1" in eku, "pinned.crt missing serverAuth EKU (has $eku). $hint")
        val sans = cert.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1) as? String }
        assertTrue("localhost" in sans, "pinned.crt missing DNS:localhost SAN (has $sans). $hint")
    }

    @Test
    fun pinnedSha256FixtureMatchesLeafDer() {
        val cert = load("pinned.crt")
        val expected = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        val stored =
            this::class.java.classLoader
                .getResourceAsStream("certs/pinned.sha256")!!
                .use { it.readBytes().decodeToString().trim() }
        assertEquals(expected, stored, "pinned.sha256 does not match SHA-256(pinned.crt DER). $hint")
    }

    @Test
    fun expiredFixtureIsExpiredButOtherwiseCompliant() {
        val cert = load("pinned-expired.crt")
        // Expired (so it isolates NotTemporallyValid) but short-lived + P-256 so no earlier branch fires.
        assertTrue(cert.notAfter.time < System.currentTimeMillis(), "pinned-expired.crt is not expired. $hint")
        assertTrue(validityDays(cert) <= 14, "pinned-expired.crt validity is ${validityDays(cert)} days, must be ≤ 14. $hint")
        assertTrue(isEcP256(cert), "pinned-expired.crt is not EC P-256. $hint")
    }

    @Test
    fun tooLongFixtureExceeds14DaysButIsCurrentlyValidAndEc() {
        val cert = load("pinned-toolong.crt")
        // > 14d (isolates ValidityPeriodTooLong, checked first) yet currently valid + P-256.
        assertTrue(validityDays(cert) > 14, "pinned-toolong.crt validity is ${validityDays(cert)} days, must be > 14. $hint")
        val nowMs = System.currentTimeMillis()
        assertTrue(cert.notBefore.time <= nowMs && nowMs <= cert.notAfter.time, "pinned-toolong.crt is not currently valid. $hint")
        assertTrue(isEcP256(cert), "pinned-toolong.crt is not EC P-256. $hint")
    }

    @Test
    fun rsaFixtureIsRsaShortLivedAndCurrentlyValid() {
        val cert = load("pinned-rsa.crt")
        // RSA (isolates UnsupportedPublicKey) but ≤14d + currently valid so earlier branches pass.
        assertEquals("RSA", cert.publicKey.algorithm, "pinned-rsa.crt is not RSA. $hint")
        assertTrue(validityDays(cert) <= 14, "pinned-rsa.crt validity is ${validityDays(cert)} days, must be ≤ 14. $hint")
        val nowMs = System.currentTimeMillis()
        assertTrue(cert.notBefore.time <= nowMs && nowMs <= cert.notAfter.time, "pinned-rsa.crt is not currently valid. $hint")
    }
}
