package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Compute the SHA-256 (FIPS 180-4) of [input]'s remaining bytes (position..limit) and write the 32 result
 * bytes into [dest] at its current position, advancing it. Reads [input] positionally — does **not** consume
 * it (its position is unchanged on return). [dest] must have at least 32 bytes remaining.
 *
 * The digest behind the W3C `serverCertificateHashes` leaf-certificate pin ([matchLeafHash]). Each backend
 * binds its own native implementation:
 *  - JVM/Android ([commonJvmMain]) → `com.ditchoom.buffer.crypto.sha256` (JCA `MessageDigest`).
 *  - Linux ([linuxMain]) → the BoringSSL `SHA256` **already linked** into this binary (quiche's libcrypto,
 *    via the `BoringSslX509` cinterop). Linux deliberately does **not** use buffer-crypto here: buffer-crypto
 *    statically bundles its *own* BoringSSL, and two BoringSSL copies in one Kotlin/Native binary collide
 *    (duplicate `SHA256_*`/`EVP_*` symbols under `--allow-multiple-definition`) → SIGSEGV at runtime.
 */
internal expect fun sha256Into(
    input: ReadBuffer,
    dest: WriteBuffer,
)
