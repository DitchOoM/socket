package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.crypto.Hkdf
import com.ditchoom.buffer.crypto.Info
import com.ditchoom.buffer.crypto.Salt
import com.ditchoom.buffer.crypto.sha256
import com.ditchoom.buffer.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One test binary that runs quiche's BoringSSL (the `ditchoom_*` wrappers) and buffer-crypto's SHA-256 and
 * HKDF together. The Linux link passes `--allow-multiple-definition`, so a second libcrypto.a would link
 * and then crash at run time: this binary running both, with agreeing results, is the proof that the
 * link carries one.
 */
class LinuxOneBoringSslTests {
    private fun buffer(vararg bytes: Int): PlatformBuffer {
        val out = BufferFactory.Default.allocate(bytes.size)
        bytes.forEach { out.writeByte(it.toByte()) }
        out.resetForRead()
        return out
    }

    @Test
    fun quicheBoringSslAndBufferCryptoAgreeOnSha256() {
        val input = buffer('a'.code, 'b'.code, 'c'.code)
        val viaQuiche = BufferFactory.Default.allocate(SHA256_BYTES)
        sha256Into(input, viaQuiche)
        viaQuiche.resetForRead()
        val viaBufferCrypto = sha256(input, BufferFactory.Default)
        assertEquals(SHA256_ABC, viaQuiche.toHexString())
        assertEquals(SHA256_ABC, viaBufferCrypto.toHexString())
    }

    @Test
    fun bufferCryptoHkdfMatchesRfc5869AfterQuicheBoringSslGeneratedAKey() {
        BoringSslPeerCertificateBackend.generateKeyPair().use { keyPair ->
            val signature = BufferFactory.Default.allocate(P256_MAX_SIGNATURE_BYTES)
            keyPair.sign(buffer(1, 2, 3), signature)
            signature.resetForRead()
            assertTrue(signature.remaining() > 0, "quiche's BoringSSL signs")
        }
        val ikm = buffer(*IntArray(RFC5869_IKM_BYTES) { RFC5869_IKM_BYTE })
        val okm = Hkdf.derive(Salt.None, ikm, Info.None, RFC5869_OKM_BYTES, BufferFactory.Default)
        assertEquals(RFC5869_CASE_3_OKM, okm.toHexString())
    }

    private companion object {
        const val SHA256_BYTES = 32
        const val SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val RFC5869_IKM_BYTES = 22
        const val RFC5869_IKM_BYTE = 0x0b
        const val RFC5869_OKM_BYTES = 42
        const val RFC5869_CASE_3_OKM =
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
    }
}
