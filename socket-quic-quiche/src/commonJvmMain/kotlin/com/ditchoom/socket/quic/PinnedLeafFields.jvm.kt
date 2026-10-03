package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.EllipticCurve
import kotlin.time.Instant

/**
 * JVM/Android extraction of the W3C `serverCertificateHashes` constraint fields ([X509PinFields]) from a
 * leaf certificate's DER, via `java.security` (a battle-tested X.509 parser — no hand-rolled ASN.1). The
 * shared [checkServerCertificatePinConstraints] policy then runs over the result; this just feeds it.
 *
 * Returns `null` if the DER cannot be parsed as an X.509 certificate; the verifier maps that to a
 * fail-closed [com.ditchoom.socket.CertificateHashPinningFailure.CertificateParseFailed].
 */
internal fun parsePinnedLeafFieldsJvm(der: ReadBuffer): X509PinFields? {
    // java.security parses from bytes; this is the sanctioned ByteArray at the java.security boundary
    // (the leaf is small — already read + hashed). The buffer is positioned at the DER start.
    @Suppress("NoByteArrayInProd") // java.security CertificateFactory requires a byte[]/InputStream
    val derBytes = der.readByteArray(der.remaining())
    val cert =
        try {
            CertificateFactory.getInstance("X.509").generateCertificate(derBytes.inputStream()) as X509Certificate
        } catch (_: Exception) {
            return null
        }
    return X509PinFields(
        notBefore = Instant.fromEpochMilliseconds(cert.notBefore.time),
        notAfter = Instant.fromEpochMilliseconds(cert.notAfter.time),
        isEcP256 = cert.isEcP256(),
        keyDescription = cert.keyDescription(),
    )
}

/** True iff the subject public key is ECDSA on the NIST P-256 (secp256r1) curve — not merely 256-bit. */
private fun X509Certificate.isEcP256(): Boolean {
    val key = publicKey as? ECPublicKey ?: return false
    val p256 = nistP256Spec
    val p = key.params
    return p.curve == p256.curve && p.generator == p256.generator && p.order == p256.order && p.cofactor == p256.cofactor
}

private fun X509Certificate.keyDescription(): String =
    when (val key = publicKey) {
        is ECPublicKey -> "EC ${key.params.curve.field.fieldSize}-bit"
        is RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
        else -> key.algorithm
    }

/**
 * The NIST P-256 (secp256r1) domain parameters, from FIPS 186-4 §D.1.2.3, so the curve check is exact.
 *
 * Stated rather than asked of the JCE: `AlgorithmParameters.getInstance("EC")` has no provider on Android
 * before API 26, and resolving it there returned null, which failed every P-256 leaf as "not ECDSA P-256"
 * (seen on the API 24 emulator lane). Every type here is in the platform since API 1.
 */
internal val nistP256Spec: ECParameterSpec by lazy {
    fun hex(s: String) = BigInteger(s, 16)
    ECParameterSpec(
        EllipticCurve(
            ECFieldFp(hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")),
            hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC"),
            hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"),
        ),
        ECPoint(
            hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
            hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"),
        ),
        hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"),
        1,
    )
}
