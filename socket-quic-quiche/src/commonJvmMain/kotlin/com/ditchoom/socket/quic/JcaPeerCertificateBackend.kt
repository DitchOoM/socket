package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.toNativeData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

private const val P256_FIELD_BYTES = 32
private const val UNCOMPRESSED_POINT: Byte = 0x04

/** JVM/Android [PeerCertificateBackend]: JCA for keys and signatures, the module's SHA-256, `java.io` files. */
internal object JcaPeerCertificateBackend : PeerCertificateBackend {
    override fun generateKeyPair(): P256KeyPair =
        try {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            JcaP256KeyPair(generator.generateKeyPair())
        } catch (e: GeneralSecurityException) {
            throw PeerCertificateException(PeerCertificateFailure.KeyGenerationFailed(e.toString()), e)
        }

    override fun sha256(
        input: ReadBuffer,
        dest: WriteBuffer,
    ) = sha256Into(input, dest)

    override suspend fun <R> withPemFiles(
        certificatePem: ReadBuffer,
        privateKeyPem: ReadBuffer,
        block: suspend (QuicTlsConfig) -> R,
    ): R {
        val certificate = writeOwnerOnly("ditchoom-peer-cert", certificatePem)
        try {
            val key = writeOwnerOnly("ditchoom-peer-key", privateKeyPem)
            try {
                return block(QuicTlsConfig(certChainPath = certificate.absolutePath, privKeyPath = key.absolutePath))
            } finally {
                key.delete()
            }
        } finally {
            certificate.delete()
        }
    }

    private fun writeOwnerOnly(
        prefix: String,
        content: ReadBuffer,
    ): File {
        val file =
            try {
                createOwnerOnlyTempFile(prefix)
            } catch (e: IOException) {
                throw PeerCertificateException(PeerCertificateFailure.PemFilesFailed(e.toString()), e)
            }
        try {
            FileOutputStream(file).use { out ->
                val bytes = content.toNativeData().byteBuffer
                while (bytes.hasRemaining()) out.channel.write(bytes)
            }
            return file
        } catch (e: IOException) {
            file.delete()
            throw PeerCertificateException(PeerCertificateFailure.PemFilesFailed(e.toString()), e)
        }
    }
}

/** An empty file only the current user can read or write, created that way (never widened, then narrowed). */
internal expect fun createOwnerOnlyTempFile(prefix: String): File

/** JCA keys cannot be wiped; [close] drops nothing and the pair is released when unreachable. */
private class JcaP256KeyPair(
    private val pair: KeyPair,
) : P256KeyPair {
    override fun writePublicKey(dest: WriteBuffer) {
        val point = (pair.public as ECPublicKey).w
        dest.writeByte(UNCOMPRESSED_POINT)
        writeFieldElement(point.affineX, dest)
        writeFieldElement(point.affineY, dest)
    }

    override fun writePrivateKey(dest: WriteBuffer) = writeFieldElement((pair.private as ECPrivateKey).s, dest)

    override fun sign(
        message: ReadBuffer,
        dest: WriteBuffer,
    ) {
        @Suppress("NoByteArrayInProd") // java.security.Signature.sign() returns the DER signature as byte[]
        val signature =
            try {
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(pair.private)
                signer.update(message.toNativeData().byteBuffer)
                signer.sign()
            } catch (e: GeneralSecurityException) {
                throw PeerCertificateException(PeerCertificateFailure.SigningFailed(e.toString()), e)
            }
        dest.writeBytes(signature)
    }

    override fun close() = Unit
}

/** [value] as exactly [P256_FIELD_BYTES] big-endian bytes; the intermediate array is zeroed. */
private fun writeFieldElement(
    value: BigInteger,
    dest: WriteBuffer,
) {
    @Suppress("NoByteArrayInProd") // java.math.BigInteger.toByteArray() is JCA's only export of an EC coordinate/scalar
    val twosComplement = value.toByteArray()
    val significant = twosComplement.size - (if (twosComplement[0].toInt() == 0) 1 else 0)
    repeat(P256_FIELD_BYTES - significant) { dest.writeByte(0) }
    dest.writeBytes(twosComplement, twosComplement.size - significant, significant)
    twosComplement.fill(0)
}
