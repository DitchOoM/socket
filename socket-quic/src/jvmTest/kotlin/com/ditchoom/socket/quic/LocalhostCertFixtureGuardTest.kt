package com.ditchoom.socket.quic

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Negative guard for the committed `localhost` test identity that the QUIC CA-pinning tests
 * ([QuicServerTestSuite.pinnedCorrectCaAnchorHandshakeAndEchoSucceed] /
 * [QuicServerTestSuite.pinnedWrongCaAnchorRejectsPeer]) rely on.
 *
 * **Why this exists.** Those tests do a *real* TLS chain validation. Apple's
 * `SecTrustEvaluateWithError` rejects any server cert whose validity exceeds **398 days** as
 * `errSecCertificateNotStandardsCompliant`, while quiche/BoringSSL (JVM/Linux) accepts it. A
 * 100-year `localhost.crt` therefore passed on JVM/Linux but made every Apple handshake hang
 * (the connect stalled until idle-timeout). This test makes that class of mistake fail *here*,
 * loudly and deterministically, on every platform that runs jvmTest (Linux/Windows/macOS) —
 * instead of as a mysterious Apple-only timeout. It also fails ~45 days before the (necessarily
 * short-lived) cert expires, so the expiry is a scheduled chore, never a surprise CI flake.
 *
 * Runs on the JVM only (full X.509 parsing); the fixture is shared, so guarding it once covers
 * the Apple consumers too. To fix a failure: re-run `socket-quic/testcerts/gen-localhost-cert.sh`.
 */
class LocalhostCertFixtureGuardTest {
    private fun loadLocalhostCert(): X509Certificate {
        val stream =
            this::class.java.classLoader.getResourceAsStream("certs/localhost.crt")
                ?: error("certs/localhost.crt not on the test classpath")
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    private val regenHint = "Regenerate with socket-quic/testcerts/gen-localhost-cert.sh, then ./gradlew :socket-quic:generateTestP12."

    @Test
    fun localhostCertValidityWithinApple398DayLimit() {
        val cert = loadLocalhostCert()
        val days = (cert.notAfter.time - cert.notBefore.time) / (24L * 60 * 60 * 1000)
        // Apple's hard ceiling is 398 days; require ≤397 so a freshly generated cert (which we
        // mint at exactly 397) is unambiguously under the limit. A long-lived cert (e.g. the old
        // 100-year fixture) trips this — that is the regression this guard exists to catch.
        assertTrue(
            days <= 397,
            "localhost.crt validity is $days days; Apple's SecTrust rejects TLS certs > 398 days " +
                "as not-standards-compliant, which silently breaks Apple QUIC cert-pinning. $regenHint",
        )
    }

    @Test
    fun localhostCertNotNearingExpiry() {
        val cert = loadLocalhostCert()
        // The cert is intentionally short-lived (≤397 days) to satisfy Apple; warn early so it is
        // refreshed before it can flake CI. 45 days of runway is comfortably more than a release
        // cadence.
        val msRemaining = cert.notAfter.time - System.currentTimeMillis()
        val daysRemaining = msRemaining / (24L * 60 * 60 * 1000)
        if (daysRemaining < 45) {
            fail("localhost.crt expires in $daysRemaining days (notAfter=${cert.notAfter}). $regenHint")
        }
    }

    @Test
    fun localhostCertCopiesAreInSync() {
        // The fixture is duplicated per consumer (Apple/Linux read testcerts/, Android reads its
        // own resources/). If a regen updates one copy but not the others — exactly how the
        // original 100-year cert lingered on the JVM classpath after testcerts/ was fixed — the
        // platforms validate different certs. Assert every copy is byte-identical to the one this
        // (JVM) test loaded. jvmTest runs with the module dir as cwd, so the sibling copies resolve
        // relative to it; if they don't (unexpected cwd), skip rather than false-fail.
        val classpathBytes =
            this::class.java.classLoader
                .getResourceAsStream("certs/localhost.crt")!!
                .use { it.readBytes() }
        val siblings =
            listOf(
                "testcerts/localhost.crt",
                "src/androidInstrumentedTest/resources/certs/localhost.crt",
            ).map { java.io.File(it) }
        val present = siblings.filter { it.exists() }
        if (present.isEmpty()) return // unexpected cwd — nothing to compare against
        for (f in present) {
            assertTrue(
                f.readBytes().contentEquals(classpathBytes),
                "${f.path} differs from the JVM-classpath localhost.crt — fixture copies drifted. $regenHint",
            )
        }
    }

    @Test
    fun localhostCertHasServerAuthAndLocalhostSan() {
        val cert = loadLocalhostCert()
        // serverAuth EKU (1.3.6.1.5.5.7.3.1) + a `localhost` SAN are both required for the cert to
        // be usable as a TLS server identity that validates against the "localhost" connect host.
        val eku = cert.extendedKeyUsage ?: emptyList()
        assertTrue("1.3.6.1.5.5.7.3.1" in eku, "localhost.crt missing serverAuth EKU (has $eku). $regenHint")
        val sanDnsNames = cert.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1) as? String }
        assertTrue("localhost" in sanDnsNames, "localhost.crt missing DNS:localhost SAN (has $sanDnsNames). $regenHint")
    }
}
