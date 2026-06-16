@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
import kotlin.time.Clock
import kotlin.time.Instant

/** SHA-256 digest length in bytes — the only algorithm `serverCertificateHashes` defines. */
private const val SHA256_DIGEST_BYTES = 32

// W3C serverCertificateHashes pins the leaf certificate, which is small; start modest and only grow if
// quiche reports a larger DER. The cap guards against a pathological/hostile size (a real leaf is < 4 KiB).
private const val INITIAL_PEER_CERT_CAPACITY = 2048
private const val MAX_PEER_CERT_CAPACITY = 1 shl 16 // 64 KiB

/**
 * Enforce W3C `serverCertificateHashes` leaf-certificate pinning post-handshake (Option 1), shared by
 * every quiche connect path (JVM/Android via [JvmQuicConnection], Linux via LinuxQuicConnection). No-op
 * when no hashes are pinned. Reads the peer's leaf DER via [readPeerCertDer] (which routes through the
 * driver loop, serialized with all other conn access), hashes it (SHA-256) and compares against [pinned].
 * On any failure it [closeConnection]s and throws [CertificateHashPinningException] (with a structured
 * [CertificateHashPinningFailure]), so an unverified connection is never handed back.
 *
 * [readPeerCertDer] is snprintf-style: copies the DER into the buffer at `addr` when it fits within
 * `capacity` and returns its length; returns a larger length (no copy) when it does not fit; 0 = no cert.
 *
 * Under the default [CertificateHashVerification.HashOnly] the quiche handshake ran with `verify_peer`
 * off (see [applyQuicOptions]); this leaf-hash match is the sole trust check, matching the browser.
 *
 * Once the hash matches, the W3C `serverCertificateHashes` certificate constraints (validity ≤ 14 days,
 * currently valid, ECDSA P-256) are enforced — but only when [parseLeafFields] is supplied. Each backend
 * passes its own native X.509 parser (java.security on JVM/Android, BoringSSL on Linux); a backend whose
 * parser is not yet wired passes `null`, in which case the constraints are not yet enforced (hash-only,
 * the prior behaviour). A non-null parser that returns `null` on a leaf that already hash-matched is a
 * fail-closed [CertificateHashPinningFailure.CertificateParseFailed].
 */
internal suspend fun verifyServerCertificateHashes(
    pinned: List<CertificateHash>,
    bufferFactory: BufferFactory,
    readPeerCertDer: suspend (addr: Long, capacity: Int) -> Int,
    closeConnection: suspend () -> Unit,
    parseLeafFields: ((der: ReadBuffer) -> X509PinFields?)? = null,
    now: Instant = Clock.System.now(),
) {
    if (pinned.isEmpty()) return
    try {
        var capacity = INITIAL_PEER_CERT_CAPACITY
        while (true) {
            val derBuf = bufferFactory.allocate(capacity)
            try {
                val addr = derBuf.nativeMemoryAccess!!.nativeAddress.toLong()
                val len = readPeerCertDer(addr, capacity)
                when {
                    len <= 0 -> throw CertificateHashPinningException(CertificateHashPinningFailure.NoPeerCertificate)
                    len > capacity -> {
                        // snprintf-style: the DER did not fit; grow (bounded) and re-read. A certificate
                        // larger than the (already generous) cap can't be obtained to verify — fail closed.
                        if (len > MAX_PEER_CERT_CAPACITY) {
                            throw CertificateHashPinningException(
                                CertificateHashPinningFailure.CertificateTooLarge(len, MAX_PEER_CERT_CAPACITY),
                            )
                        }
                        capacity = len
                        continue
                    }
                    else -> {
                        derBuf.position(len)
                        derBuf.resetForRead()
                        val match = matchLeafHash(derBuf, pinned, bufferFactory)
                        if (!match.matched) {
                            throw CertificateHashPinningException(
                                CertificateHashPinningFailure.HashMismatch(pinned.size, match.computedLeafHash),
                            )
                        }
                        // The leaf is the operator's own pinned cert; enforce the W3C constraints on it so
                        // native accepts exactly what a browser would. `matchLeafHash` is non-consuming;
                        // rewind to the DER start (position 0) — keeping the limit at `len` — before parsing.
                        if (parseLeafFields != null) {
                            derBuf.position(0)
                            val fields =
                                parseLeafFields(derBuf)
                                    ?: throw CertificateHashPinningException(
                                        CertificateHashPinningFailure.CertificateParseFailed("native X.509 parser returned no fields"),
                                    )
                            checkServerCertificatePinConstraints(fields, now)?.let {
                                throw CertificateHashPinningException(it)
                            }
                        }
                        return
                    }
                }
            } finally {
                derBuf.freeNativeMemory()
            }
        }
    } catch (t: Throwable) {
        // Verification failed (or the read errored) — never return an unverified connection.
        runCatching { closeConnection() }
        throw t
    }
}

/** Result of hashing the leaf DER once: whether it matched a pin, plus the algorithm-prefixed hex of the
 *  computed digest (for [CertificateHashPinningFailure.HashMismatch]). */
internal class LeafHashMatch(
    val matched: Boolean,
    val computedLeafHash: String,
)

/**
 * Compute the SHA-256 of [leafCertDer]'s remaining bytes **once** (W3C `serverCertificateHashes`), compare
 * it to each pinned [hashes] entry via [ReadBuffer.contentEquals], and return both the verdict and the
 * `"sha-256:<hex>"` of the leaf. Non-consuming on [leafCertDer] and every pinned hash. Only `"sha-256"`
 * is supported (enforced by [CertificateHash]).
 *
 * [bufferFactory] supplies a scratch buffer for the digest; it is freed before returning. `sha256` is the
 * swappable seam from `socket-quic` (mirrors the planned `com.ditchoom:buffer` member), shared cross-module
 * because both modules already depend on buffer.
 */
internal fun matchLeafHash(
    leafCertDer: ReadBuffer,
    hashes: List<CertificateHash>,
    bufferFactory: BufferFactory,
): LeafHashMatch {
    val digest = bufferFactory.allocate(SHA256_DIGEST_BYTES)
    return try {
        leafCertDer.sha256(digest)
        digest.resetForRead()
        val matched = hashes.any { it.value.contentEquals(digest) }
        LeafHashMatch(matched, "sha-256:" + digest.toLowerHex())
    } finally {
        digest.freeNativeMemory()
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex of this buffer's remaining bytes. Positional (`get`) — does not consume the buffer. */
private fun ReadBuffer.toLowerHex(): String {
    val end = position() + remaining()
    val sb = StringBuilder((end - position()) * 2)
    var i = position()
    while (i < end) {
        val b = this[i].toInt() and 0xFF
        sb.append(HEX_DIGITS[b ushr 4]).append(HEX_DIGITS[b and 0xF])
        i++
    }
    return sb.toString()
}
