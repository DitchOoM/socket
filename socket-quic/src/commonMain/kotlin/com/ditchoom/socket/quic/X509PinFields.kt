@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.CertificateHashPinningFailure
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The X.509 leaf-certificate fields the W3C `serverCertificateHashes` constraints need, **extracted
 * per-platform** by a battle-tested parser (java.security on JVM/Android, Security.framework on Apple,
 * BoringSSL on Linux) — there is no hand-rolled ASN.1 here. The constraint *policy* over these fields is
 * shared ([checkServerCertificatePinConstraints]).
 *
 * @property notBefore / [notAfter] the certificate's validity window.
 * @property isEcP256 true iff the subject public key is ECDSA on the NIST P-256 (secp256r1) curve — the
 *   only key the W3C `serverCertificateHashes` spec permits.
 * @property keyDescription a short human description of the actual key (e.g. `"RSA-2048"`, `"EC secp384r1"`)
 *   used in the failure message when [isEcP256] is false.
 */
class X509PinFields(
    val notBefore: Instant,
    val notAfter: Instant,
    val isEcP256: Boolean,
    val keyDescription: String,
)

/** W3C `serverCertificateHashes` maximum leaf validity period (2 weeks). */
val MAX_PINNED_CERTIFICATE_VALIDITY = 14.days

/**
 * Apply the W3C `serverCertificateHashes` certificate constraints to the per-platform-extracted [fields]
 * at [now]: validity period ≤ [MAX_PINNED_CERTIFICATE_VALIDITY], currently within the validity window, and
 * an ECDSA P-256 key. Returns the violating [CertificateHashPinningFailure], or `null` if all are
 * satisfied. Shared by every backend so native accepts exactly the certificates a browser would.
 *
 * Only called once the leaf's hash has already matched a pin, so [fields] describe the operator's own
 * pinned certificate — this is a policy check on a trusted certificate, not trust establishment.
 */
fun checkServerCertificatePinConstraints(
    fields: X509PinFields,
    now: Instant = Clock.System.now(),
): CertificateHashPinningFailure? {
    val validity = fields.notAfter - fields.notBefore
    if (validity > MAX_PINNED_CERTIFICATE_VALIDITY) {
        return CertificateHashPinningFailure.ValidityPeriodTooLong(validity, MAX_PINNED_CERTIFICATE_VALIDITY)
    }
    if (now < fields.notBefore || now > fields.notAfter) {
        return CertificateHashPinningFailure.NotTemporallyValid(fields.notBefore, fields.notAfter, now)
    }
    if (!fields.isEcP256) {
        return CertificateHashPinningFailure.UnsupportedPublicKey(fields.keyDescription)
    }
    return null
}
