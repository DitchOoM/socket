@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.boringssl.ditchoom_sha256
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCPointer

private const val SHA256_DIGEST_BYTES = 32

/**
 * Linux (Kotlin/Native) SHA-256 via the BoringSSL `SHA256` already linked into this binary — the same
 * libcrypto quiche and the [parsePinnedLeafFieldsLinux] X.509 parser use — through the `ditchoom_sha256`
 * C wrapper (see `BoringSslX509.def`). Linux deliberately does **not** route through buffer-crypto: it
 * bundles its *own* static BoringSSL, and a second libcrypto in the same K/N binary collides with quiche's
 * (duplicate `SHA256_*` symbols under `--allow-multiple-definition`) → SIGSEGV. JVM/Android have no such
 * conflict (buffer-crypto is pure-JCA there); see the commonJvmMain actual.
 *
 * The leaf DER read+hashed by the verifier is always a native-memory buffer (allocated to hand quiche a
 * `nativeAddress`), so the fast pointer path always applies; a non-native input fails closed via the
 * verifier's catch. Non-consuming on [input] (reads positionally; its position is unchanged).
 */
internal actual fun sha256Into(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    val base =
        input.nativeMemoryAccess?.nativeAddress?.toLong()
            ?: error("SHA-256 on Linux requires a native-memory input buffer")
    val len = input.remaining()
    val start = base + input.position()
    memScoped {
        val out = allocArray<UByteVar>(SHA256_DIGEST_BYTES)
        ditchoom_sha256(start.toCPointer<UByteVar>(), len.convert(), out)
        for (i in 0 until SHA256_DIGEST_BYTES) dest.writeByte(out[i].toByte())
    }
}
