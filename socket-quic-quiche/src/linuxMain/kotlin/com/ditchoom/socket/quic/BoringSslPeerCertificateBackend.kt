@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.boringssl.ditchoom_p256_generate
import com.ditchoom.socket.quic.boringssl.ditchoom_p256_sign
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer

private const val P256_SCALAR_BYTES = 32
private const val P256_POINT_BYTES = 65

/**
 * Linux [PeerCertificateBackend]: the BoringSSL already linked into this binary (the `ditchoom_p256_*`
 * wrappers in `BoringSslX509.def`), the module's SHA-256, `mkstemp` files. Never buffer-crypto: a second
 * static BoringSSL in one K/N binary collides with this one (see [sha256Into]).
 */
internal object BoringSslPeerCertificateBackend : PeerCertificateBackend {
    override fun generateKeyPair(): P256KeyPair {
        val native = BufferFactory.network()
        val scalar = native.allocate(P256_SCALAR_BYTES)
        val point = native.allocate(P256_POINT_BYTES)
        if (ditchoom_p256_generate(scalar.pointer(), point.pointer()) != 1) {
            scalar.zeroAndFree()
            point.freeNativeMemory()
            throw PeerCertificateException(PeerCertificateFailure.KeyGenerationFailed("EC_KEY_generate_key failed"))
        }
        return BoringSslP256KeyPair(scalar, point)
    }

    override fun sha256(
        input: ReadBuffer,
        dest: WriteBuffer,
    ) = sha256Into(input, dest)

    override suspend fun <R> withPemFiles(
        certificatePem: ReadBuffer,
        privateKeyPem: ReadBuffer,
        block: suspend (QuicTlsConfig) -> R,
    ): R = withPosixPemFiles(certificatePem, privateKeyPem, block)
}

/** [scalar] and [point] were filled by `ditchoom_p256_generate`; [close] zeroes the scalar. */
private class BoringSslP256KeyPair(
    private val scalar: PlatformBuffer,
    private val point: PlatformBuffer,
) : P256KeyPair {
    override fun writePublicKey(dest: WriteBuffer) {
        for (i in 0 until P256_POINT_BYTES) dest.writeByte(point[i])
    }

    override fun writePrivateKey(dest: WriteBuffer) {
        for (i in 0 until P256_SCALAR_BYTES) dest.writeByte(scalar[i])
    }

    override fun sign(
        message: ReadBuffer,
        dest: WriteBuffer,
    ) {
        val signature = BufferFactory.network().allocate(P256_MAX_SIGNATURE_BYTES)
        try {
            val messageStart = message.nativeMemoryAccess!!.nativeAddress + message.position()
            val length =
                ditchoom_p256_sign(
                    scalar.pointer(),
                    messageStart.toCPointer<UByteVar>(),
                    message.remaining().convert(),
                    signature.pointer(),
                    P256_MAX_SIGNATURE_BYTES.convert(),
                ).toInt()
            if (length == 0) throw PeerCertificateException(PeerCertificateFailure.SigningFailed("ECDSA_sign failed"))
            for (i in 0 until length) dest.writeByte(signature[i])
        } finally {
            signature.freeNativeMemory()
        }
    }

    override fun close() {
        scalar.zeroAndFree()
        point.freeNativeMemory()
    }
}

private fun PlatformBuffer.pointer(): CPointer<UByteVar> = nativeMemoryAccess!!.nativeAddress.toCPointer()!!

private fun PlatformBuffer.zeroAndFree() {
    for (i in 0 until capacity) set(i, 0.toByte())
    freeNativeMemory()
}
