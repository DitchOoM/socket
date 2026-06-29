package com.ditchoom.socket.quic

import com.ditchoom.socket.CertificateHashPinningFailure
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Deterministic boundary coverage for the shared W3C `serverCertificateHashes` constraint policy
 * [checkServerCertificatePinConstraints]. The policy is a pure function over per-platform-extracted
 * [X509PinFields] + an injected `now`, so the exact day-precision edges (incl. *exactly* 14 days, which
 * a real generated cert can't reliably hit) live here rather than in the e2e per-platform tests — those
 * only need to prove each native parser feeds the policy correctly.
 *
 * Check order is validity → temporal → key, so each case below isolates one branch by keeping the
 * earlier ones satisfied.
 */
class ServerCertificatePinConstraintsTests {
    private val now = Instant.fromEpochSeconds(1_700_000_000) // fixed reference instant

    private fun fields(
        notBefore: Instant = now - 1.days,
        notAfter: Instant = now + 1.days,
        isEcP256: Boolean = true,
        keyDescription: String = "EC secp256r1",
    ) = X509PinFields(notBefore, notAfter, isEcP256, keyDescription)

    // --- validity period (≤ 14 days) ---

    @Test
    fun validityWellWithinLimitPasses() {
        assertNull(checkServerCertificatePinConstraints(fields(notBefore = now - 1.days, notAfter = now + 12.days), now))
    }

    @Test
    fun validityExactly14DaysPasses() {
        // The boundary is inclusive: 14d is `> 14d` == false. Only a real cert can't hit this exactly.
        assertNull(checkServerCertificatePinConstraints(fields(notBefore = now, notAfter = now + 14.days), now))
    }

    @Test
    fun validityOneSecondOver14DaysFails() {
        val failure = checkServerCertificatePinConstraints(fields(notBefore = now, notAfter = now + 14.days + 1.seconds), now)
        assertIs<CertificateHashPinningFailure.ValidityPeriodTooLong>(failure)
    }

    @Test
    fun validity15DaysFails() {
        // Reported before any temporal/key issue even though this cert is currently valid + P-256.
        val failure = checkServerCertificatePinConstraints(fields(notBefore = now, notAfter = now + 15.days), now)
        assertIs<CertificateHashPinningFailure.ValidityPeriodTooLong>(failure)
    }

    // --- temporal validity (now within [notBefore, notAfter]) ---

    @Test
    fun expiredCertFails() {
        val failure = checkServerCertificatePinConstraints(fields(notBefore = now - 2.days, notAfter = now - 1.days), now)
        assertIs<CertificateHashPinningFailure.NotTemporallyValid>(failure)
    }

    @Test
    fun notYetValidCertFails() {
        val failure = checkServerCertificatePinConstraints(fields(notBefore = now + 1.days, notAfter = now + 5.days), now)
        assertIs<CertificateHashPinningFailure.NotTemporallyValid>(failure)
    }

    @Test
    fun nowAtNotBeforeBoundaryPasses() {
        assertNull(checkServerCertificatePinConstraints(fields(notBefore = now, notAfter = now + 5.days), now))
    }

    @Test
    fun nowAtNotAfterBoundaryPasses() {
        assertNull(checkServerCertificatePinConstraints(fields(notBefore = now - 5.days, notAfter = now), now))
    }

    // --- key constraint (ECDSA P-256) ---

    @Test
    fun ecP256KeyPasses() {
        assertNull(checkServerCertificatePinConstraints(fields(isEcP256 = true), now))
    }

    @Test
    fun rsaKeyFails() {
        val failure = checkServerCertificatePinConstraints(fields(isEcP256 = false, keyDescription = "RSA-2048"), now)
        val unsupported = assertIs<CertificateHashPinningFailure.UnsupportedPublicKey>(failure)
        // The human detail surfaces what was found so the operator can see why it was rejected.
        assertIs<String>(unsupported.detail)
    }

    @Test
    fun ecNonP256CurveFails() {
        val failure = checkServerCertificatePinConstraints(fields(isEcP256 = false, keyDescription = "EC secp384r1"), now)
        assertIs<CertificateHashPinningFailure.UnsupportedPublicKey>(failure)
    }

    // --- a fully-compliant cert passes every constraint ---

    @Test
    fun compliantCertPasses() {
        assertNull(
            checkServerCertificatePinConstraints(
                fields(notBefore = now - 1.days, notAfter = now + 12.days, isEcP256 = true),
                now,
            ),
        )
    }
}
