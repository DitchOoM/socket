@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.boringssl.ditchoom_x509_pin_fields
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlin.time.Instant

/**
 * Linux (Kotlin/Native) extraction of the W3C `serverCertificateHashes` constraint fields
 * ([X509PinFields]) from a leaf certificate's DER, via BoringSSL's X.509 parser (the same
 * battle-tested ASN.1 decoder quiche links — no hand-rolled ASN.1). The actual extraction runs in
 * the `ditchoom_x509_pin_fields` C wrapper (see `BoringSslX509.def`); the shared
 * [checkServerCertificatePinConstraints] policy then runs over the result. This just marshals the
 * DER pointer in and the fields out.
 *
 * Returns `null` if the DER cannot be parsed; the verifier maps that to a fail-closed
 * [com.ditchoom.socket.CertificateHashPinningFailure.CertificateParseFailed].
 */
internal fun parsePinnedLeafFieldsLinux(der: ReadBuffer): X509PinFields? {
    val base = der.nativeMemoryAccess?.nativeAddress?.toLong() ?: return null
    val len = der.remaining().toLong()
    if (len <= 0L) return null
    val start = base + der.position()

    return memScoped {
        val notBefore = alloc<LongVar>()
        val notAfter = alloc<LongVar>()
        val isEcP256 = alloc<IntVar>()
        val keyBaseId = alloc<IntVar>()
        val curveNid = alloc<IntVar>()

        val ok =
            ditchoom_x509_pin_fields(
                start.toCPointer<UByteVar>(),
                len,
                notBefore.ptr,
                notAfter.ptr,
                isEcP256.ptr,
                keyBaseId.ptr,
                curveNid.ptr,
            )
        if (ok != 1) return@memScoped null

        // out_curve_nid is non-zero only for EC keys; fall back to the base key id otherwise.
        val keyDescription =
            if (curveNid.value != 0) "EC curve nid=${curveNid.value}" else "key base_id=${keyBaseId.value}"
        X509PinFields(
            notBefore = Instant.fromEpochSeconds(notBefore.value),
            notAfter = Instant.fromEpochSeconds(notAfter.value),
            isEcP256 = isEcP256.value == 1,
            keyDescription = keyDescription,
        )
    }
}
