@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.CertificateHashPinningFailure
import com.ditchoom.socket.quic.nwhelpers.ditchoom_apple_pin_leaf_fields
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.time.Instant

/**
 * Run the W3C `serverCertificateHashes` certificate constraints on the matched leaf DER the NW
 * verify_block captured, returning the violating [CertificateHashPinningFailure] or `null` when the leaf
 * satisfies them. Called post-handshake (after the leaf already hash-matched in the verify_block), only on
 * platforms that report [ServerCertificateConstraintSupport.Enforced] (macOS). Fails closed: a missing /
 * unparseable DER is a [CertificateHashPinningFailure.CertificateParseFailed] rather than a silently
 * skipped constraint.
 *
 * The leaf fields are extracted by the platform-native Security.framework parser ([ditchoom_apple_pin_leaf_fields],
 * which mirrors the Linux BoringSSL wrapper); only the parse is per-platform, the constraint policy
 * ([checkServerCertificatePinConstraints]) stays in shared common code.
 *
 * [leafDerOut]/[leafDerLen] are the verify_block's snprintf-style out-params: [leafDerLen] is the leaf's
 * true DER length, copied into [leafDerOut] only when it fit the [capacity]-byte capture buffer.
 */
internal fun checkApplePinnedCertificateConstraints(
    leafDerOut: CPointer<UByteVar>?,
    leafDerLen: Int,
    capacity: Int,
): CertificateHashPinningFailure? {
    if (leafDerOut == null || leafDerLen <= 0) {
        return CertificateHashPinningFailure.CertificateParseFailed(
            "Network.framework verify_block captured no matched leaf certificate",
        )
    }
    if (leafDerLen > capacity) {
        // Pathological: a real leaf DER is < 4 KiB, well under the capture buffer. Fail closed.
        return CertificateHashPinningFailure.CertificateParseFailed(
            "matched leaf DER ($leafDerLen bytes) exceeds the $capacity-byte capture buffer",
        )
    }
    val fields =
        extractAppleLeafFields(leafDerOut, leafDerLen)
            ?: return CertificateHashPinningFailure.CertificateParseFailed(
                "Security.framework could not extract the leaf certificate fields",
            )
    return checkServerCertificatePinConstraints(fields)
}

/** EC over a NIST prime-random curve, as Security.framework reports it (see [ditchoom_apple_pin_leaf_fields]). */
private const val APPLE_KEY_TYPE_EC = 1
private const val APPLE_KEY_TYPE_RSA = 2

/** P-256 key size in bits — 256-bit prime-random EC is secp256r1 on Apple's SecKey. */
private const val P256_KEY_SIZE_BITS = 256

private fun extractAppleLeafFields(
    der: CPointer<UByteVar>,
    length: Int,
): X509PinFields? =
    memScoped {
        val notBefore = alloc<DoubleVar>()
        val notAfter = alloc<DoubleVar>()
        val keyType = alloc<IntVar>()
        val keySizeBits = alloc<IntVar>()
        val ok =
            ditchoom_apple_pin_leaf_fields(
                der,
                length,
                notBefore.ptr,
                notAfter.ptr,
                keyType.ptr,
                keySizeBits.ptr,
            )
        if (ok == 0) return@memScoped null

        val isEc = keyType.value == APPLE_KEY_TYPE_EC
        val isRsa = keyType.value == APPLE_KEY_TYPE_RSA
        val sizeBits = keySizeBits.value
        X509PinFields(
            notBefore = Instant.fromEpochMilliseconds((notBefore.value * 1000.0).toLong()),
            notAfter = Instant.fromEpochMilliseconds((notAfter.value * 1000.0).toLong()),
            isEcP256 = isEc && sizeBits == P256_KEY_SIZE_BITS,
            keyDescription =
                when {
                    isEc -> "EC $sizeBits-bit (ECSECPrimeRandom)"
                    isRsa -> "RSA-$sizeBits"
                    else -> "unsupported key type"
                },
        )
    }
