@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.CertificateHashPinningFailure
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The X.509 leaf-certificate fields the W3C `serverCertificateHashes` constraints need. Extraction is
 * per-backend — `java.security` on JVM/Android, BoringSSL on Linux, and on Apple (which has no usable
 * native option: `SecCertificateCopyValues` is macOS-only and the portable replacement is above K/N's
 * deployment floor) a shared, hand-rolled **structural** DER walk in `:socket-quic-quiche`'s commonMain.
 * That walk reads only the fields below — no signatures, no names, no key material.
 *
 * The walk's correctness evidence is differential, the same standard this repo requires of its
 * hand-rolled HTTP/3 + QPACK codec: `PinnedLeafFieldsDerDifferentialTest` asserts field-for-field
 * agreement with `java.security` over 127 real certificates. Note that corpus's actual scope: 121
 * self-signed Mozilla NSS roots plus 6 OpenSSL-generated fixtures, every one of which encodes its
 * validity as a seconds-precision, `Z`-terminated UTCTime or GeneralizedTime and its key as a
 * namedCurve EC or an RSA key. It therefore contains **none** of the three shapes on which the walk is
 * known to differ from `java.security`: an EC key with explicit (`specifiedCurve`) domain parameters, a
 * UTCTime without seconds, and a GeneralizedTime with fractional seconds or a numeric UTC offset. Those
 * three are not left latent — each is pinned by an explicit frozen-hex vector in that same test, which
 * asserts what both parsers actually do, so a change in either is a test failure rather than a
 * behavioural surprise on a real deployment. The constraint *policy* over these fields is shared
 * ([checkServerCertificatePinConstraints]).
 *
 * These fields describe a leaf whose SHA-256 already matched an operator-supplied pin, so reading them
 * is policy on an already-trusted certificate, never trust establishment.
 *
 * @property notBefore / [notAfter] the certificate's validity window.
 * @property isEcP256 true iff the subject public key is ECDSA on the NIST P-256 (secp256r1) curve — the
 *   only key the W3C `serverCertificateHashes` spec permits.
 * @property keyDescription an **opaque, platform-specific diagnostic string** naming the key that was
 *   actually presented, surfaced in [CertificateHashPinningFailure.UnsupportedPublicKey] when
 *   [isEcP256] is false. Its format is deliberately **not** stable and must not be parsed or matched on:
 *   each backend reports whatever its own extractor knows. Today JVM/Android produce `java.security`
 *   descriptions (`"RSA-2048"`, `"EC 256-bit"`, or the bare algorithm name), the shared DER walk
 *   produces a dotted OID (`"1.2.840.10045.3.1.7"` for an EC curve, the algorithm OID otherwise), and
 *   Linux produces BoringSSL numeric ids (`"EC curve nid=415"`, `"key base_id=6"`). Treat it as text for
 *   a human reading a failure, and branch on [isEcP256] for anything programmatic.
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
