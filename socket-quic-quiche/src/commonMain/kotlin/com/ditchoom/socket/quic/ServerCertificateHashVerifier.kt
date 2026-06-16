package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SSLHandshakeFailedException

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
 * On any failure — no peer certificate, a too-large certificate, a hash mismatch, or a backend that
 * cannot read the peer cert — it [closeConnection]s and throws [SSLHandshakeFailedException], so an
 * unverified connection is never handed back.
 *
 * [readPeerCertDer] is snprintf-style: copies the DER into the buffer at `addr` when it fits within
 * `capacity` and returns its length; returns a larger length (no copy) when it does not fit; 0 = no cert.
 *
 * Under the default [CertificateHashVerification.HashOnly] the quiche handshake ran with `verify_peer`
 * off (see [applyQuicOptions]); this leaf-hash match is the sole trust check, matching the browser.
 */
internal suspend fun verifyServerCertificateHashes(
    pinned: List<CertificateHash>,
    bufferFactory: BufferFactory,
    readPeerCertDer: suspend (addr: Long, capacity: Int) -> Int,
    closeConnection: suspend () -> Unit,
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
                    len <= 0 ->
                        throw SSLHandshakeFailedException(
                            "Peer presented no certificate to match against serverCertificateHashes",
                        )
                    len > capacity -> {
                        // snprintf-style: the DER did not fit; grow (bounded) and re-read.
                        if (len > MAX_PEER_CERT_CAPACITY) {
                            throw SSLHandshakeFailedException(
                                "Peer leaf certificate ($len bytes) exceeds the maximum for hash pinning",
                            )
                        }
                        capacity = len
                        continue
                    }
                    else -> {
                        derBuf.position(len)
                        derBuf.resetForRead()
                        if (!serverCertificateLeafHashMatches(derBuf, pinned, bufferFactory)) {
                            throw SSLHandshakeFailedException(
                                "Server certificate hash did not match any pinned serverCertificateHashes",
                            )
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

/**
 * True if the SHA-256 of [leafCertDer]'s remaining bytes equals the [CertificateHash.value] of any
 * pinned [hashes] (W3C `serverCertificateHashes` leaf-hash pinning). Computes the digest **once** and
 * compares it to each pinned hash via [ReadBuffer.contentEquals]. Non-consuming on [leafCertDer] and
 * on every pinned hash. Only `"sha-256"` is supported (enforced by [CertificateHash]).
 *
 * [bufferFactory] supplies a scratch buffer for the computed digest; it is freed before returning.
 *
 * `sha256` is the swappable seam from `socket-quic` (mirrors the planned `com.ditchoom:buffer` member);
 * shared cross-module because both modules already depend on buffer.
 */
internal fun serverCertificateLeafHashMatches(
    leafCertDer: ReadBuffer,
    hashes: List<CertificateHash>,
    bufferFactory: BufferFactory,
): Boolean {
    val digest = bufferFactory.allocate(SHA256_DIGEST_BYTES)
    return try {
        leafCertDer.sha256(digest)
        digest.resetForRead()
        hashes.any { it.value.contentEquals(digest) }
    } finally {
        digest.freeNativeMemory()
    }
}
