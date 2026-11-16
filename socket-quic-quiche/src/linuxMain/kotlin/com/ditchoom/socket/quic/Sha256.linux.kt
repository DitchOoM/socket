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
import com.ditchoom.buffer.crypto.sha256 as bufferCryptoSha256

private const val SHA256_DIGEST_BYTES = 32

/**
 * Linux (Kotlin/Native) SHA-256 via the BoringSSL `SHA256` already linked into this binary — the same
 * libcrypto quiche and the [parsePinnedLeafFieldsLinux] X.509 parser use — through the `ditchoom_sha256`
 * C wrapper (see `BoringSslX509.def`). buffer-crypto's Linux klib links the same canonical libcrypto.a,
 * so one copy of BoringSSL is in the binary. JVM/Android call JCA `MessageDigest` directly; see the
 * commonJvmMain actual.
 *
 * A native-memory [input] (the leaf DER the verifier hands quiche, the address-token work buffer) is hashed
 * in place through its address. Any other buffer is hashed by buffer-crypto's SHA-256 over the same
 * libcrypto: no copy either way. Non-consuming on [input] (its position is unchanged).
 */
internal actual fun sha256Into(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    val base = input.nativeMemoryAccess?.nativeAddress?.toLong()
    if (base == null) {
        val start = input.position()
        bufferCryptoSha256(input, dest)
        input.position(start)
        return
    }
    val len = input.remaining()
    val start = base + input.position()
    memScoped {
        val out = allocArray<UByteVar>(SHA256_DIGEST_BYTES)
        ditchoom_sha256(start.toCPointer<UByteVar>(), len.convert(), out)
        for (i in 0 until SHA256_DIGEST_BYTES) dest.writeByte(out[i].toByte())
    }
}
