package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.ReadBuffer

/**
 * A pinned server **leaf**-certificate hash (W3C WebTransport `serverCertificateHashes`): the session is
 * accepted only if the peer's TLS leaf certificate hashes to one of these. Set on
 * [WebTransportOptions.serverCertificateHashes].
 *
 * This is the **neutral** form, honored by both backings:
 * - **Browser** — passed straight to `new WebTransport(url, { serverCertificateHashes: [{ algorithm, value }] })`,
 *   where it is the sole trust check (the browser does no chain validation for these).
 * - **Native** — verified against the peer's leaf DER, by default as the sole trust check too
 *   (browser parity), so a self-signed / ephemeral leaf works identically on both. The native-only
 *   `Http3WebTransportConfig` can opt into *also* requiring chain validation (defense in depth).
 *
 * @property value the raw hash bytes. For `"sha-256"` this is exactly 32 bytes; the buffer is read from
 *   its current [ReadBuffer.position] to its limit and is not consumed.
 * @property algorithm the hash algorithm. Only `"sha-256"` (the WebTransport spec's only value) is
 *   supported.
 */
class WebTransportCertificateHash(
    val value: ReadBuffer,
    val algorithm: String = "sha-256",
) {
    init {
        require(algorithm == "sha-256") {
            "Unsupported certificate hash algorithm '$algorithm' (only \"sha-256\")"
        }
        require(value.remaining() == SHA256_BYTES) {
            "sha-256 certificate hash must be $SHA256_BYTES bytes, got ${value.remaining()}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebTransportCertificateHash) return false
        return algorithm == other.algorithm && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        // Content-based, consistent with equals (ReadBuffer has no contentHashCode). Positional reads
        // (get(index)) over the remaining bytes — does not consume the buffer. Cheap for a 32-byte hash.
        var result = algorithm.hashCode()
        val end = value.position() + value.remaining()
        var i = value.position()
        while (i < end) {
            result = 31 * result + value[i].toInt()
            i++
        }
        return result
    }

    override fun toString(): String = "WebTransportCertificateHash($algorithm, ${value.remaining()}B)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}
