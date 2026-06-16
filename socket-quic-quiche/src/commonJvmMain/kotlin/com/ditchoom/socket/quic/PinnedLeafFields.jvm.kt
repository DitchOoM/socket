package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import java.security.AlgorithmParameters
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
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
    val p256 = nistP256Spec ?: return false
    val p = key.params
    return p.curve == p256.curve && p.generator == p256.generator && p.order == p256.order && p.cofactor == p256.cofactor
}

private fun X509Certificate.keyDescription(): String =
    when (val key = publicKey) {
        is ECPublicKey -> "EC ${key.params.curve.field.fieldSize}-bit"
        is RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
        else -> key.algorithm
    }

/** The canonical NIST P-256 parameters, resolved once from the JCE so the curve check is exact. */
private val nistP256Spec: ECParameterSpec? by lazy {
    try {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    } catch (_: Exception) {
        null
    }
}
