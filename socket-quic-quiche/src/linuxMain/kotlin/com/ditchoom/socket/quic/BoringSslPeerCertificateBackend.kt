@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.secure
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
 * wrappers in `BoringSslX509.def`), the module's SHA-256, `mkstemp` files. The private scalar lives in a
 * [secure] buffer, wiped when the key pair closes.
 */
internal object BoringSslPeerCertificateBackend : PeerCertificateBackend {
    override fun generateKeyPair(): P256KeyPair {
        val scalar = BufferFactory.network().secure(maxAllocationBytes = P256_SCALAR_BYTES).allocate(P256_SCALAR_BYTES)
        val point = BufferFactory.network().allocate(P256_POINT_BYTES)
        if (ditchoom_p256_generate(scalar.pointer(), point.pointer()) != 1) {
            scalar.freeNativeMemory()
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

/** [scalar] and [point] were filled by `ditchoom_p256_generate`; freeing the secure [scalar] zeroes it. */
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
        val access = message.nativeMemoryAccess
        if (access != null) {
            signAt((access.nativeAddress + message.position()).toCPointer<UByteVar>(), message.remaining(), dest)
            return
        }
        // Not native memory: staged into some, because the certificate body is small and signed once.
        val staged = stageNative(message)
        try {
            signAt(staged.nativeMemoryAccess?.nativeAddress?.toCPointer<UByteVar>(), staged.remaining(), dest)
        } finally {
            staged.freeNativeMemory()
        }
    }

    private fun signAt(
        messageStart: CPointer<UByteVar>?,
        messageLength: Int,
        dest: WriteBuffer,
    ) {
        val signature = BufferFactory.network().allocate(P256_MAX_SIGNATURE_BYTES)
        try {
            val length =
                ditchoom_p256_sign(
                    scalar.pointer(),
                    messageStart,
                    messageLength.convert(),
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
        scalar.freeNativeMemory()
        point.freeNativeMemory()
    }
}

private fun PlatformBuffer.pointer(): CPointer<UByteVar> = nativeMemoryAccess!!.nativeAddress.toCPointer()!!

/** A native-memory copy of [message]'s remaining bytes, read-ready; [message]'s position is unchanged. */
private fun stageNative(message: ReadBuffer): PlatformBuffer {
    val start = message.position()
    val staged = BufferFactory.network().allocate(message.remaining())
    staged.write(message)
    message.position(start)
    staged.resetForRead()
    return staged
}
