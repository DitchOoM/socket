package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hermetic (no network, no file IO, no generated fixtures) proof that the shared DER leaf-field walk
 * [parsePinnedLeafFieldsDer] — the parser that flipped Apple to
 * [ServerCertificateConstraintSupport.Enforced] (issue #339) — actually works when compiled by
 * Kotlin/Native, over a real-world corpus rather than the frozen vectors in `PinnedLeafFieldsDerTests`.
 *
 * The corpus is [MOZILLA_CA_ROOTS_PEM], the 121-root bundle already embedded in the Apple klib for
 * `verifyPeer=true` trust (see `MozillaCaRootsTest`). Because it needs nothing from the filesystem this
 * runs on **macosArm64 and iosSimulatorArm64 alike** — unlike the end-to-end
 * `AppleQuicCertificateHashPinningTests`, which needs `testcerts/` and therefore skips on a simulator.
 * That makes this the only real-certificate coverage the parser gets on iOS.
 *
 * Agreement with a reference parser (`java.security`) over this same corpus is asserted on the JVM by
 * `PinnedLeafFieldsDerDifferentialTest`; here the assertion is that K/N walks all 121 without a null
 * and produces sane, ordered validity windows.
 */
class AppleMozillaCaDerParseTest {
    @Test
    fun walksEveryEmbeddedMozillaRoot() {
        val blocks = PEM_BLOCK.findAll(MOZILLA_CA_ROOTS_PEM).map { it.value }.toList()
        assertTrue(blocks.size >= 100, "expected >=100 embedded Mozilla roots, found ${blocks.size}")
        var parsed = 0
        blocks.forEachIndexed { index, block ->
            val der = base64Decode(block.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----"))
            val buf = BufferFactory.deterministic().allocate(der.size)
            der.forEach { buf.writeByte(it) }
            buf.resetForRead()
            val fields = assertNotNull(parsePinnedLeafFieldsDer(buf), "root #$index (${der.size} DER bytes) failed to parse")
            assertTrue(fields.notBefore < fields.notAfter, "root #$index has a reversed validity window")
            parsed++
        }
        assertEquals(blocks.size, parsed)
    }

    /** Test-local RFC 4648 decoder (tests may use ByteArray freely; the parser itself allocates none). */
    private fun base64Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        for (c in text) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) continue // newlines, padding
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((accumulator shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
