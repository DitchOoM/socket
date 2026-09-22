@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFAllocatorNull
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.posix.memset

private const val P256_BITS = 256
private const val P256_SCALAR_BYTES = 32

/**
 * Apple [PeerCertificateBackend]: Security.framework in-memory keys (never stored in a keychain) and
 * `SecKeyCreateSignature`, BoringSSL's SHA-256 from libquiche.a, `mkstemp` files.
 */
internal object SecurityFrameworkPeerCertificateBackend : PeerCertificateBackend {
    override fun generateKeyPair(): P256KeyPair {
        val attributes =
            CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                ?: throw PeerCertificateException(PeerCertificateFailure.KeyGenerationFailed("CFDictionaryCreateMutable returned NULL"))
        try {
            CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            return memScoped {
                val bits = alloc<IntVar> { value = P256_BITS }
                val number = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, bits.ptr)
                CFDictionarySetValue(attributes, kSecAttrKeySizeInBits, number)
                number?.let { CFRelease(it) }
                val error = alloc<CFErrorRefVar>()
                val key =
                    SecKeyCreateRandomKey(attributes, error.ptr)
                        ?: throw PeerCertificateException(
                            PeerCertificateFailure.KeyGenerationFailed("SecKeyCreateRandomKey: ${describe(error.value)}"),
                        )
                SecKeyP256KeyPair(key)
            }
        } finally {
            CFRelease(attributes)
        }
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

private class SecKeyP256KeyPair(
    private val key: SecKeyRef,
) : P256KeyPair {
    override fun writePublicKey(dest: WriteBuffer) {
        val publicKey =
            SecKeyCopyPublicKey(key)
                ?: throw PeerCertificateException(PeerCertificateFailure.KeyGenerationFailed("SecKeyCopyPublicKey returned NULL"))
        try {
            val point =
                SecKeyCopyExternalRepresentation(publicKey, null)
                    ?: throw PeerCertificateException(
                        PeerCertificateFailure.KeyGenerationFailed("public SecKeyCopyExternalRepresentation returned NULL"),
                    )
            try {
                copy(point, 0, CFDataGetLength(point).toInt(), dest)
            } finally {
                CFRelease(point)
            }
        } finally {
            CFRelease(publicKey)
        }
    }

    /** The private export is ANSI X9.63 `04 ‖ X ‖ Y ‖ K`; the scalar is the trailing 32 bytes. The export is zeroed. */
    override fun writePrivateKey(dest: WriteBuffer) {
        val x963 =
            SecKeyCopyExternalRepresentation(key, null)
                ?: throw PeerCertificateException(
                    PeerCertificateFailure.KeyGenerationFailed("private SecKeyCopyExternalRepresentation returned NULL"),
                )
        try {
            val length = CFDataGetLength(x963).toInt()
            copy(x963, maxOf(0, length - P256_SCALAR_BYTES), length, dest)
        } finally {
            memset(CFDataGetBytePtr(x963), 0, CFDataGetLength(x963).convert())
            CFRelease(x963)
        }
    }

    override fun sign(
        message: ReadBuffer,
        dest: WriteBuffer,
    ) {
        val start = message.nativeMemoryAccess!!.nativeAddress + message.position()
        val data =
            CFDataCreateWithBytesNoCopy(kCFAllocatorDefault, start.toCPointer<UByteVar>(), message.remaining().convert(), kCFAllocatorNull)
                ?: throw PeerCertificateException(PeerCertificateFailure.SigningFailed("CFDataCreateWithBytesNoCopy returned NULL"))
        try {
            memScoped {
                val error = alloc<CFErrorRefVar>()
                val signature =
                    SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, data, error.ptr)
                        ?: throw PeerCertificateException(
                            PeerCertificateFailure.SigningFailed("SecKeyCreateSignature: ${describe(error.value)}"),
                        )
                try {
                    copy(signature, 0, CFDataGetLength(signature).toInt(), dest)
                } finally {
                    CFRelease(signature)
                }
            }
        } finally {
            CFRelease(data)
        }
    }

    override fun close() = CFRelease(key)
}

private fun copy(
    data: CFDataRef,
    from: Int,
    to: Int,
    dest: WriteBuffer,
) {
    val bytes = CFDataGetBytePtr(data) ?: return
    for (i in from until to) dest.writeByte(bytes[i].toByte())
}

/** A CFError's code, releasing it; Security.framework hands back an owned reference on failure. */
private fun describe(error: CFErrorRef?): String =
    if (error == null) {
        "no CFError"
    } else {
        val code = CFErrorGetCode(error)
        CFRelease(error)
        "CFError code=$code"
    }
