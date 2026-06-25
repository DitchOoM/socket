@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_identity_from_p12
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_identity_leaf_info
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Network.framework QUIC **server** anti-amplification cert guard for the OS-26 Swift listener
 * ([buildAppleQuicSwiftServer]). Single source of truth: the flight estimate, the budget, and the
 * actionable error message live here once.
 *
 * **Why:** Apple's libquic under-credits a non-Apple client's first flight for RFC 9000 §8.1, so an
 * oversized (notably RSA-2048) server certificate flight can never be delivered and the QUIC handshake
 * silently deadlocks against quiche/Chrome (see issue
 * [nw-server-quiche-client-amplification-deadlock]). The guard estimates the leaf's TLS flight at bind
 * time and throws a clear error for an oversized leaf, turning a 10s timeout into an instant, actionable
 * failure. A small EC P-256 leaf passes; [QuicOptions.appleAllowOversizedServerCert] bypasses the guard.
 */

/**
 * Throw [IllegalArgumentException] at bind time when the server's TLS leaf certificate (imported from
 * [p12Path]) would overflow Network.framework's anti-amplification budget. No-op when [allowOversized]
 * is set, or when the leaf cannot be inspected (fail open — the actual identity import for the listener
 * is handled by each backend separately, so the guard never blocks a valid-but-unassessable identity).
 */
internal fun guardAppleServerCertFlight(
    p12Path: String,
    p12Password: String,
    allowOversized: Boolean,
) {
    if (allowOversized) return
    // Inspect the leaf inline (DER length + key type) via the cinterop leaf-info helper, so the opaque
    // sec_identity_t type stays inferred. Importing the PKCS#12 again here (memory-only, no
    // keychain side effects) is a one-shot bind-time cost.
    val p12Data = NSData.create(contentsOfFile = p12Path) ?: return
    val identity = nw_helper_quic_identity_from_p12(p12Data, p12Password) ?: return
    val leafInfo =
        memScoped {
            val keyTypeOut = alloc<IntVar>()
            val derLenOut = alloc<IntVar>()
            if (nw_helper_quic_identity_leaf_info(identity, keyTypeOut.ptr, derLenOut.ptr) == 0) {
                null
            } else {
                keyTypeOut.value to derLenOut.value
            }
        }
    if (leafInfo != null) {
        rejectOversizedAppleServerCert(keyType = leafInfo.first, leafDerLen = leafInfo.second, p12Path = p12Path)
    }
}

/** [nw_helper_quic_identity_leaf_info] key-type codes: leaf public key is RSA (the deadlock-prone case). */
private const val NW_LEAF_KEY_TYPE_RSA = 2

/**
 * Network.framework's effective server amplification budget for the first flight (~1 KB; see issue
 * [nw-server-quiche-client-amplification-deadlock]). An estimated TLS flight above this can't be delivered
 * to a non-Apple client.
 */
private const val NW_AMPLIFICATION_BUDGET = 1000

/** Approximate CertificateVerify signature + handshake framing for an RSA-2048 leaf (~256B sig). */
private const val RSA_CERT_VERIFY_BYTES = 260

/** Approximate CertificateVerify signature + handshake framing for an EC P-256 leaf (~72B sig). */
private const val EC_CERT_VERIFY_BYTES = 80

/** Fixed per-flight overhead beyond the leaf DER + CertificateVerify (EncryptedExtensions, Finished, headers). */
private const val TLS_FLIGHT_FIXED_OVERHEAD = 260

/**
 * Throw a clear bind-time error when the server's TLS leaf certificate would overflow Network.framework's
 * anti-amplification budget and deadlock a non-Apple QUIC client. Takes the leaf's [keyType]
 * ([nw_helper_quic_identity_leaf_info] codes) and [leafDerLen] and estimates the handshake flight.
 * EC P-256 fits; RSA-2048 does not. Bypassed by [QuicOptions.appleAllowOversizedServerCert].
 */
internal fun rejectOversizedAppleServerCert(
    keyType: Int,
    leafDerLen: Int,
    p12Path: String,
) {
    val isRsa = keyType == NW_LEAF_KEY_TYPE_RSA
    val certVerifyBytes = if (isRsa) RSA_CERT_VERIFY_BYTES else EC_CERT_VERIFY_BYTES
    val estimatedFlight = leafDerLen + certVerifyBytes + TLS_FLIGHT_FIXED_OVERHEAD
    if (estimatedFlight <= NW_AMPLIFICATION_BUDGET) return

    val keyDesc = if (isRsa) "RSA" else "non-EC-P-256"
    throw IllegalArgumentException(
        "Apple Network.framework QUIC server: the TLS leaf certificate from $p12Path ($keyDesc, ${leafDerLen}B " +
            "DER, ~${estimatedFlight}B estimated handshake flight) exceeds Network.framework's " +
            "~${NW_AMPLIFICATION_BUDGET}B anti-amplification budget. Apple's libquic under-credits a non-Apple " +
            "client's first flight (RFC 9000 §8.1), so this certificate cannot be delivered and the QUIC " +
            "handshake will deadlock against quiche/Chrome clients. Present a small EC (ECDSA P-256) leaf with a " +
            "minimal chain instead. If this server will serve Apple clients exclusively (Apple↔Apple is " +
            "unaffected), set QuicOptions.appleAllowOversizedServerCert = true to bypass this guard.",
    )
}
