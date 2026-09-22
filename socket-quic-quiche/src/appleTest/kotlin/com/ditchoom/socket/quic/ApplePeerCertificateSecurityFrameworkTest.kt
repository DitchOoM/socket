@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Generated certificates judged by Security.framework, which shares no code with the encoder: it must
 * accept the DER as a certificate, extract its public key, and verify the self-signature over the
 * `TBSCertificate` bytes with that key. Hermetic, so it runs on the iOS simulator as well as macOS.
 */
class ApplePeerCertificateSecurityFrameworkTest {
    @Test
    fun securityFrameworkParsesAndVerifiesTheSelfSignature() {
        val engine = peerCertificates as? PeerCertificateSupport.Available ?: fail("peerCertificates=$peerCertificates")
        repeat(CERTIFICATES) { index ->
            engine.generate().use { certificate ->
                val der = certificate.der().let { buffer -> ByteArray(buffer.remaining()) { buffer[buffer.position() + it] } }
                val context = "certificate $index: ${der.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}"
                val (tbs, signature) = splitCertificate(der)
                val derData = cfData(der)
                val tbsData = cfData(tbs)
                val signatureData = cfData(signature)
                try {
                    val secCertificate =
                        assertNotNull(SecCertificateCreateWithData(null, derData), "SecCertificateCreateWithData refused $context")
                    try {
                        val publicKey = assertNotNull(SecCertificateCopyKey(secCertificate), "SecCertificateCopyKey refused $context")
                        try {
                            assertTrue(
                                SecKeyVerifySignature(
                                    publicKey,
                                    kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                                    tbsData,
                                    signatureData,
                                    null,
                                ),
                                "self-signature does not verify: $context",
                            )
                        } finally {
                            CFRelease(publicKey)
                        }
                    } finally {
                        CFRelease(secCertificate)
                    }
                } finally {
                    CFRelease(derData)
                    CFRelease(tbsData)
                    CFRelease(signatureData)
                }
            }
        }
    }

    /**
     * `Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue BIT STRING }` split
     * by a test-local TLV reader: the encoded TBSCertificate, and the BIT STRING content minus its
     * unused-bits octet (the DER ECDSA-Sig-Value).
     */
    private fun splitCertificate(der: ByteArray): Pair<ByteArray, ByteArray> {
        val outer = tlv(der, 0)
        assertEquals(0x30, outer.tag)
        val tbs = tlv(der, outer.contentStart)
        val algorithm = tlv(der, tbs.end)
        val signature = tlv(der, algorithm.end)
        assertEquals(0x03, signature.tag)
        assertEquals(0, der[signature.contentStart].toInt(), "BIT STRING unused bits")
        assertEquals(outer.end, signature.end)
        return der.copyOfRange(outer.contentStart, tbs.end) to der.copyOfRange(signature.contentStart + 1, signature.end)
    }

    private class Tlv(
        val tag: Int,
        val contentStart: Int,
        val end: Int,
    )

    private fun tlv(
        der: ByteArray,
        at: Int,
    ): Tlv {
        val tag = der[at].toInt() and 0xFF
        val first = der[at + 1].toInt() and 0xFF
        if (first < 0x80) return Tlv(tag, at + 2, at + 2 + first)
        val octets = first and 0x7F
        var length = 0
        for (i in 0 until octets) length = (length shl 8) or (der[at + 2 + i].toInt() and 0xFF)
        return Tlv(tag, at + 2 + octets, at + 2 + octets + length)
    }

    private fun cfData(bytes: ByteArray): CFDataRef =
        bytes.usePinned { pinned ->
            assertNotNull(CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.convert()))
        }

    private companion object {
        const val CERTIFICATES = 16
    }
}
