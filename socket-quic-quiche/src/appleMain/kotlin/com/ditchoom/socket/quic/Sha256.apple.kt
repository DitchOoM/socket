@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.bssl.SHA256
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCPointer

private const val SHA256_DIGEST_BYTES = 32

/**
 * Apple (Kotlin/Native) SHA-256 via BoringSSL's one-shot `SHA256`, vendored into and force-loaded from
 * libquiche.a (see [BoringSslSha256.def]). Same "reuse the already-linked crypto" approach as
 * [Sha256.linux.kt], and collision-free on Apple because quiche's BoringSSL is the only crypto library
 * present (buffer-crypto is scoped to the JVM/Android path). Mirrors the linux contract: non-consuming on
 * [input] (reads positionally; its position is unchanged), writes 32 bytes into [dest] at its current
 * position.
 *
 * The leaf DER read+hashed by the verifier is always a native-memory buffer (allocated to hand quiche a
 * `nativeAddress`), so the fast pointer path always applies; a non-native input fails closed via the
 * verifier's catch.
 */
internal actual fun sha256Into(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    val base =
        input.nativeMemoryAccess?.nativeAddress?.toLong()
            ?: error("SHA-256 on Apple requires a native-memory input buffer")
    val len = input.remaining()
    val start = base + input.position()
    memScoped {
        val out = allocArray<UByteVar>(SHA256_DIGEST_BYTES)
        SHA256(start.toCPointer<UByteVar>(), len.convert(), out)
        for (i in 0 until SHA256_DIGEST_BYTES) dest.writeByte(out[i].toByte())
    }
}
