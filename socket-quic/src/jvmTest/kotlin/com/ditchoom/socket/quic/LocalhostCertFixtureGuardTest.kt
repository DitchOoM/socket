package com.ditchoom.socket.quic

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Negative guard for the `localhost` test identity that the QUIC CA-pinning tests
 * ([QuicServerTestSuite.pinnedCorrectCaAnchorHandshakeAndEchoSucceed] /
 * [QuicServerTestSuite.pinnedWrongCaAnchorRejectsPeer]) pin.
 *
 * The cert is generated fresh each build by the `:socket-quic:generateLocalhostCert` Gradle task,
 * so it can never expire — but those CA-pinning tests do a *real* TLS chain validation that is
 * unforgiving in two stack-specific ways, and a future tweak to the generation args could silently
 * break one platform while the other stays green:
 *   * Apple's `SecTrustEvaluateWithError` rejects any cert with validity > 398 days as
 *     `errSecCertificateNotStandardsCompliant` — a too-long `-validity` would hang every Apple
 *     handshake while quiche/BoringSSL (lenient) kept passing.
 *   * quiche/BoringSSL only trusts a self-signed cert as a pinned ANCHOR when it is `CA:TRUE`.
 * This asserts the generated cert keeps the shape both stacks need. Runs on the JVM only (full
 * X.509 parsing); the fixture is shared, so guarding it once covers the Apple/Android consumers
 * too. A failure means `generateLocalhostCert`'s keytool args drifted — fix them there.
 */
class LocalhostCertFixtureGuardTest {
    private fun loadLocalhostCert(): X509Certificate {
        val stream =
            this::class.java.classLoader.getResourceAsStream("certs/localhost.crt")
                ?: error("certs/localhost.crt not on the test classpath — did :socket-quic:generateLocalhostCert run?")
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    private val hint = "Fix the keytool args in the :socket-quic:generateLocalhostCert task (socket-quic/build.gradle.kts)."

    @Test
    fun localhostCertValidityWithinApple398DayLimit() {
        val cert = loadLocalhostCert()
        val days = (cert.notAfter.time - cert.notBefore.time) / (24L * 60 * 60 * 1000)
        // generateLocalhostCert mints exactly 397 days; anything beyond Apple's 398-day ceiling
        // would silently break Apple QUIC cert-pinning (errSecCertificateNotStandardsCompliant).
        assertTrue(
            days <= 398,
            "localhost.crt validity is $days days; Apple SecTrust rejects TLS certs > 398 days. $hint",
        )
    }

    @Test
    fun localhostCertIsCaTrueForQuicheAnchorTrust() {
        val cert = loadLocalhostCert()
        // basicConstraints pathLen >= 0 means CA:TRUE; getBasicConstraints() returns -1 for a
        // non-CA cert. quiche/BoringSSL rejects a CA:FALSE self-signed cert as a pinned anchor.
        assertTrue(
            cert.basicConstraints >= 0,
            "localhost.crt is not CA:TRUE (basicConstraints=${cert.basicConstraints}); quiche won't accept it as a pinned anchor. $hint",
        )
    }

    @Test
    fun localhostCertHasServerAuthAndLocalhostSan() {
        val cert = loadLocalhostCert()
        // serverAuth EKU (1.3.6.1.5.5.7.3.1) + a `localhost` SAN are both required for the cert to
        // validate as a TLS server identity against the "localhost" connect host.
        val eku = cert.extendedKeyUsage ?: emptyList()
        assertTrue("1.3.6.1.5.5.7.3.1" in eku, "localhost.crt missing serverAuth EKU (has $eku). $hint")
        val sanDnsNames = cert.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1) as? String }
        assertTrue("localhost" in sanDnsNames, "localhost.crt missing DNS:localhost SAN (has $sanDnsNames). $hint")
    }
}
