@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Instant

/** DER tag bytes this walk recognises. Anything else in a structural slot is a parse failure. */
private const val TAG_SEQUENCE = 0x30
private const val TAG_OID = 0x06
private const val TAG_EXPLICIT_VERSION = 0xA0 // [0] EXPLICIT Version, TBSCertificate's optional first field
private const val TAG_UTC_TIME = 0x17
private const val TAG_GENERALIZED_TIME = 0x18

private const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1" // id-ecPublicKey
private const val OID_PRIME256V1 = "1.2.840.10045.3.1.7" // secp256r1 / NIST P-256

/** RFC 5280 4.1.2.5.1: a UTCTime `YY` of 50..99 means 19YY, 00..49 means 20YY. */
private const val UTC_TIME_CENTURY_PIVOT = 50

private const val UTC_TIME_LENGTH = 13 // YYMMDDHHMMSSZ
private const val GENERALIZED_TIME_LENGTH = 15 // YYYYMMDDHHMMSSZ

/** Base-128 continuation bit / payload mask for an OID sub-identifier octet. */
private const val OID_CONTINUES = 0x80
private const val OID_PAYLOAD = 0x7F
private const val OID_SHIFT = 7

private const val BYTE_MASK = 0xFF
private const val LONG_FORM = 0x80 // length octet: 0x80 bit set = long form
private const val LONG_FORM_COUNT = 0x7F
private const val INDEFINITE_LENGTH = 0x80
private const val RESERVED_LENGTH = 0xFF
private const val MAX_LENGTH_OCTETS = 4 // more than this cannot fit a (non-negative) Int
private const val HIGH_TAG_NUMBER = 0x1F // low 5 bits all set = multi-byte tag

/** First-arc thresholds for the packed `first * 40 + second` OID encoding. */
private const val OID_ARC1_LIMIT = 40L
private const val OID_ARC2_LIMIT = 80L

/**
 * Shared, allocation-free extraction of the W3C `serverCertificateHashes` constraint fields
 * ([X509PinFields]) from a leaf certificate's DER — a **structural** ASN.1 walk with no platform
 * dependencies, so every quiche backend (including the Apple targets, which have no usable native
 * X.509 parser: see [serverCertificateConstraintSupport]) can enforce the constraints.
 *
 * It reads only what the three W3C constraints need — `notBefore`, `notAfter`, and the SPKI
 * algorithm/curve OIDs — and deliberately does **not** verify signatures, decode names, or enter the
 * `subjectPublicKey` BIT STRING. It is not a general X.509 parser and must never be used as one: this
 * is a policy read over a leaf whose SHA-256 already matched an operator-supplied pin
 * ([verifyServerCertificateHashes] hashes and matches *before* calling this), never trust
 * establishment. All calendar arithmetic is delegated to [Instant.parseOrNull]; only the DER framing
 * is hand-rolled.
 *
 * Correctness evidence is differential, the same standard this repo applies to its hand-rolled
 * HTTP/3 + QPACK codec: `PinnedLeafFieldsDerDifferentialTest` asserts field-for-field agreement with
 * `java.security` ([parsePinnedLeafFieldsJvm]) over 127 real certificates — the 121 vendored Mozilla
 * roots plus the six generated test fixtures — and `PinnedLeafFieldsDerTests` pins frozen-hex positive
 * and malformed-input vectors on every target.
 *
 * That corpus is all seconds-precision `Z`-terminated times and namedCurve-or-RSA keys, so agreement
 * over it is not a claim of agreement everywhere. Three shapes are known to differ and are pinned by
 * their own frozen-hex vectors in the differential test rather than left latent: an EC key with
 * explicit (`specifiedCurve`) domain parameters, a UTCTime without seconds, and a GeneralizedTime with
 * fractional seconds or a numeric UTC offset. On the first this walk parses where `java.security`
 * refuses the certificate outright; on the other two it returns `null` where `java.security` parses.
 * Every one of those outcomes is a rejected connection on this backend — they differ in which typed
 * [com.ditchoom.socket.CertificateHashPinningFailure] the caller sees, not in whether an unwanted
 * certificate is accepted.
 *
 * Totality — not DER conformance — is the hardening target: every length is bound-checked by
 * subtraction (`len > end - i`, so a hostile length cannot overflow into a pass), indefinite/reserved
 * lengths and multi-byte tags are rejected outright, all reads go through the bounds-checked
 * [ReadBuffer.get] (never `getUnchecked`), and the whole body is wrapped so a buffer underflow becomes
 * a fail-closed [com.ditchoom.socket.CertificateHashPinningFailure.CertificateParseFailed] rather than
 * escaping the verifier as a raw buffer exception. It is correspondingly *permissive* where strictness
 * would buy nothing — notably non-minimal long-form lengths, which DER forbids and this accepts; see
 * [header] for why that is safe on an already-hash-matched certificate.
 *
 * Reads absolutely over `[der.position(), der.limit())` and does not consume [der]. Allocates no
 * intermediate byte array of any kind — the DER is read in place and never leaves the buffer, so the
 * module's no-byte-array-in-production rule needs no suppression here (grep this file for the type name
 * and you will find none).
 *
 * Returns `null` if the DER cannot be walked.
 */
internal fun parsePinnedLeafFieldsDer(der: ReadBuffer): X509PinFields? =
    try {
        walkLeaf(der)
    } catch (_: Exception) {
        // A malformed/truncated leaf can drive ReadBuffer.get past the buffer; fail closed like a
        // structural reject rather than surfacing a buffer exception out of the verifier.
        null
    }

/**
 * A definite-length DER TLV: its [tag] byte, the absolute index its content starts at, and the
 * absolute index one past the end of that content (which, lengths being definite, is also one past
 * the end of the whole TLV — so [end] is where the next sibling begins).
 */
private class Tlv(
    val tag: Int,
    val start: Int,
    val end: Int,
)

/**
 * Walk `Certificate → TBSCertificate → {validity, subjectPublicKeyInfo}` (RFC 5280 4.1), skipping
 * every field the constraints do not read. Returns `null` on any structural mismatch.
 */
private fun walkLeaf(der: ReadBuffer): X509PinFields? {
    val certificate = header(der, der.position(), der.limit()) ?: return null
    if (certificate.tag != TAG_SEQUENCE) return null
    val tbs = header(der, certificate.start, certificate.end) ?: return null
    if (tbs.tag != TAG_SEQUENCE) return null
    val tbsEnd = tbs.end

    // TBSCertificate ::= SEQUENCE { [0] version DEFAULT v1, serialNumber, signature, issuer,
    //                               validity, subject, subjectPublicKeyInfo, ... }
    // The version tag is optional; bind the first header once and reuse it as serialNumber when the
    // [0] wrapper is absent (a v1 leaf, e.g. testcerts/cert.crt) rather than re-decoding it.
    var field = header(der, tbs.start, tbsEnd) ?: return null
    if (field.tag == TAG_EXPLICIT_VERSION) {
        field = header(der, field.end, tbsEnd) ?: return null
    }
    var i = field.end // past serialNumber
    i = (header(der, i, tbsEnd) ?: return null).end // past signature (AlgorithmIdentifier)
    i = (header(der, i, tbsEnd) ?: return null).end // past issuer (Name)

    // Validity ::= SEQUENCE { notBefore Time, notAfter Time }
    val validity = header(der, i, tbsEnd) ?: return null
    if (validity.tag != TAG_SEQUENCE) return null
    val notBeforeTlv = header(der, validity.start, validity.end) ?: return null
    val notBefore = readTime(notBeforeTlv, der) ?: return null
    val notAfterTlv = header(der, notBeforeTlv.end, validity.end) ?: return null
    val notAfter = readTime(notAfterTlv, der) ?: return null

    i = (header(der, validity.end, tbsEnd) ?: return null).end // past subject (Name)

    // SubjectPublicKeyInfo ::= SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }
    // Only the AlgorithmIdentifier is read; the BIT STRING is never entered, so there is no
    // unused-bits handling anywhere in this file.
    val spki = header(der, i, tbsEnd) ?: return null
    if (spki.tag != TAG_SEQUENCE) return null
    val algorithm = header(der, spki.start, spki.end) ?: return null
    if (algorithm.tag != TAG_SEQUENCE) return null
    val algorithmOidTlv = header(der, algorithm.start, algorithm.end) ?: return null
    if (algorithmOidTlv.tag != TAG_OID) return null
    val algorithmOid = readOid(der, algorithmOidTlv.start, algorithmOidTlv.end) ?: return null

    // For EC keys the curve is the AlgorithmIdentifier's namedCurve parameter. A specifiedCurve
    // (an explicit SEQUENCE of domain parameters) is not a named P-256 and is reported as such
    // rather than rejected — checkServerCertificatePinConstraints owns that verdict.
    var isEcP256 = false
    var keyDescription = algorithmOid
    if (algorithmOid == OID_EC_PUBLIC_KEY) {
        val parameters = header(der, algorithmOidTlv.end, algorithm.end)
        if (parameters != null && parameters.tag == TAG_OID) {
            val curve = readOid(der, parameters.start, parameters.end)
            if (curve != null) {
                keyDescription = curve
                isEcP256 = curve == OID_PRIME256V1
            }
        }
    }

    return X509PinFields(
        notBefore = notBefore,
        notAfter = notAfter,
        isEcP256 = isEcP256,
        keyDescription = keyDescription,
    )
}

/**
 * Decode the identifier + definite length octets at [index], bounded by [limit] (an absolute index,
 * never past `der.limit()`). Returns `null` — never throws, never advances past [limit] — for a
 * multi-byte tag, an indefinite (`0x80`) or reserved (`0xFF`) length, a length needing more than
 * [MAX_LENGTH_OCTETS] octets or one that does not fit a non-negative Int, or content that would run
 * past [limit].
 *
 * Every bound is checked by subtraction against the remaining span, so no addition of an
 * attacker-chosen length can overflow into a passing comparison.
 *
 * **Deliberately permissive about length *minimality*.** DER requires the shortest possible length
 * encoding; this accepts BER-shaped long forms too (`0x81 0x05`, `0x82 0x00 0x05` — a 5-byte content
 * spelled in one or two length octets), so it is a structural walk over BER-shaped lengths rather than
 * a DER validator. That is safe here and tightening it would be a net loss: this parser only ever sees
 * a certificate whose SHA-256 has *already* matched an operator-supplied pin — [verifyServerCertificateHashes]
 * hashes and compares before it calls in — so a non-minimal length is the operator's own certificate,
 * byte-identical to the one they pinned, not attacker-chosen input. Rejecting it would turn a working
 * pin into a `CertificateParseFailed` on some CA's unusual-but-harmless encoding, widening the
 * behavioural break for no security gain. Nothing downstream re-encodes or canonicalises these bytes,
 * so there is no signature or hash for a length ambiguity to smuggle anything past.
 */
private fun header(
    b: ReadBuffer,
    index: Int,
    limit: Int,
): Tlv? {
    var i = index
    if (i < 0 || i >= limit) return null
    val tag = b[i].toInt() and BYTE_MASK
    i++
    if (tag and HIGH_TAG_NUMBER == HIGH_TAG_NUMBER) return null // high-tag-number form: not in a leaf's skeleton
    if (i >= limit) return null
    val firstLengthOctet = b[i].toInt() and BYTE_MASK
    i++
    val length: Int
    if (firstLengthOctet < LONG_FORM) {
        length = firstLengthOctet
    } else {
        if (firstLengthOctet == INDEFINITE_LENGTH) return null // BER-only; forbidden in DER
        if (firstLengthOctet == RESERVED_LENGTH) return null
        val octets = firstLengthOctet and LONG_FORM_COUNT
        if (octets > MAX_LENGTH_OCTETS) return null
        if (octets > limit - i) return null
        var acc = 0
        var read = 0
        while (read < octets) {
            acc = (acc shl 8) or (b[i].toInt() and BYTE_MASK)
            i++
            read++
        }
        if (acc < 0) return null // 4 octets with the top bit set: does not fit a non-negative Int
        length = acc
    }
    if (length > limit - i) return null // subtraction, never `i + length` — cannot overflow
    return Tlv(tag, i, i + length)
}

/**
 * Decode an OBJECT IDENTIFIER's content octets `[start, end)` to dotted-decimal. Returns `null` for
 * empty content, a non-minimal sub-identifier (leading `0x80`), a truncated trailing sub-identifier,
 * or one too large for a Long.
 */
private fun readOid(
    b: ReadBuffer,
    start: Int,
    end: Int,
): String? {
    if (start >= end) return null
    val text = StringBuilder()
    var value = 0L
    var i = start
    var isFirstArc = true
    var inProgress = false
    while (i < end) {
        val octet = b[i].toInt() and BYTE_MASK
        if (!inProgress && octet == OID_CONTINUES) return null // non-minimal leading padding
        inProgress = true
        if (value > (Long.MAX_VALUE shr OID_SHIFT)) return null
        value = (value shl OID_SHIFT) or (octet and OID_PAYLOAD).toLong()
        if (octet and OID_CONTINUES == 0) {
            if (isFirstArc) {
                // The first two arcs are packed as `arc1 * 40 + arc2`; arc1 > 2 is impossible, so
                // any remainder above 80 belongs to arc2 of arc1 = 2.
                val arc1 =
                    if (value < OID_ARC1_LIMIT) {
                        0L
                    } else if (value < OID_ARC2_LIMIT) {
                        1L
                    } else {
                        2L
                    }
                text.append(arc1).append('.').append(value - arc1 * OID_ARC1_LIMIT)
                isFirstArc = false
            } else {
                text.append('.').append(value)
            }
            value = 0L
            inProgress = false
        }
        i++
    }
    if (inProgress) return null // last sub-identifier still had its continuation bit set
    return text.toString()
}

/**
 * Decode a `Time` TLV — RFC 5280 permits only UTCTime (`0x17`, `YYMMDDHHMMSSZ`) and GeneralizedTime
 * (`0x18`, `YYYYMMDDHHMMSSZ`), both UTC ("Z"), both to seconds precision.
 *
 * Only the DER framing is decoded here: the digits are assembled into an ISO-8601
 * `YYYY-MM-DDThh:mm:ssZ` string and handed to [Instant.parseOrNull], which owns every calendar
 * question (digit validation, month range, leap-year-aware day-of-month, hour/minute/second range).
 * Returns `null` for any other tag, a wrong length, a missing `Z`, or a string the stdlib rejects.
 */
private fun readTime(
    tlv: Tlv,
    b: ReadBuffer,
): Instant? {
    val length = tlv.end - tlv.start
    val text = StringBuilder(20)
    val monthStart: Int
    when (tlv.tag) {
        TAG_UTC_TIME -> {
            if (length != UTC_TIME_LENGTH) return null
            val tens = charAt(b, tlv.start)
            val ones = charAt(b, tlv.start + 1)
            if (tens !in '0'..'9' || ones !in '0'..'9') return null
            val yy = (tens - '0') * 10 + (ones - '0')
            text.append(if (yy >= UTC_TIME_CENTURY_PIVOT) "19" else "20").append(tens).append(ones)
            monthStart = tlv.start + 2
        }

        TAG_GENERALIZED_TIME -> {
            if (length != GENERALIZED_TIME_LENGTH) return null
            text
                .append(charAt(b, tlv.start))
                .append(charAt(b, tlv.start + 1))
                .append(charAt(b, tlv.start + 2))
                .append(charAt(b, tlv.start + 3))
            monthStart = tlv.start + 4
        }

        else -> return null
    }
    if (charAt(b, tlv.end - 1) != 'Z') return null // RFC 5280: UTC only, no local time or offset
    text
        .append('-')
        .append(charAt(b, monthStart))
        .append(charAt(b, monthStart + 1))
        .append('-')
        .append(charAt(b, monthStart + 2))
        .append(charAt(b, monthStart + 3))
        .append('T')
        .append(charAt(b, monthStart + 4))
        .append(charAt(b, monthStart + 5))
        .append(':')
        .append(charAt(b, monthStart + 6))
        .append(charAt(b, monthStart + 7))
        .append(':')
        .append(charAt(b, monthStart + 8))
        .append(charAt(b, monthStart + 9))
        .append('Z')
    return Instant.parseOrNull(text)
}

/** The byte at absolute [i] as a Latin-1 char, via the bounds-checked [ReadBuffer.get]. */
private fun charAt(
    b: ReadBuffer,
    i: Int,
): Char = (b[i].toInt() and BYTE_MASK).toChar()
