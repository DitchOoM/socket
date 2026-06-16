package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer

/** SHA-256 digest length in bytes — the only algorithm `serverCertificateHashes` defines. */
private const val SHA256_DIGEST_BYTES = 32

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
