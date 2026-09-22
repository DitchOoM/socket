@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The validity bound and the DER/PEM encoder behind [PeerCertificate], against hand-checked vectors. The
 * encoded certificate as a whole is checked by parsing it back with three independent parsers in
 * `:socket-quic-quiche` (the DER walk, `java.security`, Security.framework).
 */
class PeerCertificateEncodingTests {
    // --- PeerCertificateValidity: > 14 days is unrepresentable ---

    @Test
    fun maximumIsFourteenDays() = assertEquals(14.days, PeerCertificateValidity.Maximum.duration)

    @Test
    fun fourteenDaysExactlyIsValid() {
        val result = assertIs<PeerCertificateValidity.Result.Valid>(PeerCertificateValidity.of(14.days))
        assertEquals(PeerCertificateValidity.Maximum, result.validity)
    }

    @Test
    fun oneMillisecondOverFourteenDaysExceedsMaximum() {
        val requested = 14.days + 1.milliseconds
        val result = assertIs<PeerCertificateValidity.Result.ExceedsMaximum>(PeerCertificateValidity.of(requested))
        assertEquals(requested, result.requested)
    }

    @Test
    fun fifteenDaysExceedsMaximum() {
        assertIs<PeerCertificateValidity.Result.ExceedsMaximum>(PeerCertificateValidity.of(15.days))
    }

    @Test
    fun infiniteExceedsMaximum() {
        assertIs<PeerCertificateValidity.Result.ExceedsMaximum>(PeerCertificateValidity.of(kotlin.time.Duration.INFINITE))
    }

    @Test
    fun subSecondIsNotPositive() {
        assertIs<PeerCertificateValidity.Result.NotPositive>(PeerCertificateValidity.of(999.milliseconds))
        assertIs<PeerCertificateValidity.Result.NotPositive>(PeerCertificateValidity.of(-1.hours))
    }

    @Test
    fun truncatesToWholeSeconds() {
        val result = assertIs<PeerCertificateValidity.Result.Valid>(PeerCertificateValidity.of(90.seconds + 999.milliseconds))
        assertEquals(90.seconds, result.validity.duration)
    }

    // --- DER primitives ---

    @Test
    fun oidVectors() {
        assertEquals("06072a8648ce3d0201", hex(Der.oid("1.2.840.10045.2.1"))) // id-ecPublicKey
        assertEquals("06082a8648ce3d030107", hex(Der.oid("1.2.840.10045.3.1.7"))) // prime256v1
        assertEquals("06082a8648ce3d040302", hex(Der.oid("1.2.840.10045.4.3.2"))) // ecdsa-with-SHA256
        assertEquals("0603551d25", hex(Der.oid("2.5.29.37"))) // extKeyUsage
        assertEquals("06082b06010505070301", hex(Der.oid("1.3.6.1.5.5.7.3.1"))) // id-kp-serverAuth
    }

    @Test
    fun utcTimeThrough2049() {
        // "260921123456Z"
        assertEquals("170d3236303932313132333435365a", hex(Der.time(Instant.parse("2026-09-21T12:34:56Z"))))
        assertEquals("170d3439313233313233353935395a", hex(Der.time(Instant.parse("2049-12-31T23:59:59Z"))))
        assertEquals("170d3530303130313030303030305a", hex(Der.time(Instant.parse("1950-01-01T00:00:00Z"))))
    }

    @Test
    fun generalizedTimeFrom2050() {
        // "20500101000000Z"
        assertEquals("180f32303530303130313030303030305a", hex(Der.time(Instant.parse("2050-01-01T00:00:00Z"))))
    }

    @Test
    fun timeOutsideX509RangeIsTyped() {
        for (instant in listOf(Instant.parse("1949-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"))) {
            val failure = assertFailsWith<PeerCertificateException> { Der.time(instant) }.failure
            assertIs<PeerCertificateFailure.TimeNotEncodable>(failure)
            assertEquals(instant, failure.instant)
        }
    }

    @Test
    fun unsignedIntegerIsMinimalAndPositive() {
        assertEquals("020100", hex(Der.unsignedInteger(bytes(0x00, 0x00))))
        assertEquals("02017f", hex(Der.unsignedInteger(bytes(0x00, 0x7f))))
        assertEquals("02020080", hex(Der.unsignedInteger(bytes(0x80))))
        assertEquals("02020080", hex(Der.unsignedInteger(bytes(0x00, 0x00, 0x80))))
    }

    @Test
    fun lengthUsesShortThenMinimalLongForm() {
        assertEquals("047f", hex(Der.octetString(zeros(127))).take(4))
        assertEquals("048180", hex(Der.octetString(zeros(128))).take(6))
        assertEquals("0481ff", hex(Der.octetString(zeros(255))).take(6))
        assertEquals("04820100", hex(Der.octetString(zeros(256))).take(8))
        assertEquals(4 + 256, Der.octetString(zeros(256)).encodedLength)
    }

    @Test
    fun constructedFramesChildren() {
        assertEquals("a003020102", hex(Der.explicit(0, Der.smallInteger(2))))
        assertEquals("030200aa", hex(Der.bitString(bytes(0xaa))))
        assertEquals("0403020105", hex(Der.octetString(Der.smallInteger(5))))
        assertEquals("300602010102010a", hex(Der.sequence(Der.smallInteger(1), Der.smallInteger(10))))
    }

    @Test
    fun encodedValuesAreNotConsumed() {
        val source = bytes(0x01, 0x02, 0x03)
        hex(Der.octetString(source))
        hex(Der.bitString(source))
        assertEquals(0, source.position())
        assertEquals(3, source.remaining())
    }

    // --- PEM (RFC 7468 / RFC 4648 base64) ---

    @Test
    fun pemBase64Padding() {
        assertEquals("-----BEGIN X-----\nTWFu\n-----END X-----\n", pemText(bytes('M'.code, 'a'.code, 'n'.code)))
        assertEquals("-----BEGIN X-----\nTWE=\n-----END X-----\n", pemText(bytes('M'.code, 'a'.code)))
        assertEquals("-----BEGIN X-----\nTQ==\n-----END X-----\n", pemText(bytes('M'.code)))
    }

    @Test
    fun pemWrapsAtSixtyFourColumns() {
        // 48 bytes -> exactly one 64-char line; 49 bytes -> a second line holding "AA=="
        val one = pemText(zeros(48)).lines()
        assertEquals(listOf("-----BEGIN X-----", "A".repeat(64), "-----END X-----", ""), one)
        val two = pemText(zeros(49)).lines()
        assertEquals(listOf("-----BEGIN X-----", "A".repeat(64), "AA==", "-----END X-----", ""), two)
    }

    private fun hex(der: Der): String = der.encode(BufferFactory.Default).toHexString()

    private fun pemText(der: ReadBuffer): String {
        val out = pem("X", der, BufferFactory.Default)
        return buildString { while (out.remaining() > 0) append(out.readByte().toInt().toChar()) }
    }

    private fun bytes(vararg values: Int): ReadBuffer {
        val buffer = BufferFactory.Default.allocate(values.size)
        values.forEach { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()
        return buffer
    }

    private fun zeros(count: Int): ReadBuffer = bytes(*IntArray(count))
}
