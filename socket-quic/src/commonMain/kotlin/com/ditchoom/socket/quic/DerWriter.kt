@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlin.time.Instant

private const val TAG_INTEGER = 0x02
private const val TAG_BIT_STRING = 0x03
private const val TAG_OCTET_STRING = 0x04
private const val TAG_OID = 0x06
private const val TAG_UTF8_STRING = 0x0C
private const val TAG_UTC_TIME = 0x17
private const val TAG_GENERALIZED_TIME = 0x18
private const val TAG_SEQUENCE = 0x30
private const val TAG_SET = 0x31
private const val TAG_CONTEXT_CONSTRUCTED = 0xA0

private const val SHORT_LENGTH_LIMIT = 0x80
private const val LONG_LENGTH_FLAG = 0x80
private const val BYTE_MASK = 0xFF
private const val HIGH_BIT = 0x80
private const val OID_PAYLOAD_BITS = 7
private const val OID_PAYLOAD_MASK = 0x7FL
private const val OID_CONTINUES = 0x80
private const val OID_FIRST_ARC_FACTOR = 40L

/** RFC 5280 4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050. */
private const val FIRST_GENERALIZED_TIME_YEAR = 2050
private const val FIRST_UTC_TIME_YEAR = 1950

/** `YYYY-MM-DDThh:mm:ssZ`, the only shape [Instant.toString] gives a whole-second instant in 1950..9999. */
private const val ISO_SECONDS_LENGTH = 20

/**
 * A DER value ready to be written: its total encoded size is known before any byte is written, so a
 * whole structure is sized once and encoded into a single exact-capacity buffer. Covers only the
 * universal types an X.509 v3 certificate and a PKCS#8 EC key need.
 */
internal abstract class Der {
    abstract val encodedLength: Int

    abstract fun writeTo(dest: WriteBuffer)

    /** Encode into a new buffer from [factory], returned ready to read. */
    fun encode(factory: BufferFactory): PlatformBuffer {
        val out = factory.allocate(encodedLength)
        writeTo(out)
        out.resetForRead()
        return out
    }

    companion object {
        fun sequence(vararg children: Der): Der = Constructed(TAG_SEQUENCE, children)

        fun set(vararg children: Der): Der = Constructed(TAG_SET, children)

        /** `[tagNumber] EXPLICIT`. */
        fun explicit(
            tagNumber: Int,
            child: Der,
        ): Der = Constructed(TAG_CONTEXT_CONSTRUCTED or tagNumber, arrayOf(child))

        /** An INTEGER in `0..127`, which encodes as a single content octet. */
        fun smallInteger(value: Int): Der = Primitive(TAG_INTEGER, 1) { it.writeByte(value.toByte()) }

        /** A non-negative INTEGER from the big-endian unsigned bytes [magnitude] (not consumed). */
        fun unsignedInteger(magnitude: ReadBuffer): Der {
            var start = magnitude.position()
            val end = magnitude.limit()
            while (start < end - 1 && magnitude[start].toInt() == 0) start++
            val pad = if (magnitude[start].toInt() and HIGH_BIT != 0) 1 else 0
            return Primitive(TAG_INTEGER, pad + end - start) { dest ->
                if (pad == 1) dest.writeByte(0)
                copy(magnitude, start, end, dest)
            }
        }

        /** An OBJECT IDENTIFIER from its dotted-decimal form. */
        fun oid(dotted: String): Der {
            val arcs = dotted.split('.').map { it.toLong() }
            val subIdentifiers = listOf(arcs[0] * OID_FIRST_ARC_FACTOR + arcs[1]) + arcs.drop(2)
            val length = subIdentifiers.sumOf { base128Length(it) }
            return Primitive(TAG_OID, length) { dest -> subIdentifiers.forEach { writeBase128(it, dest) } }
        }

        /** A UTF8String of 7-bit ASCII [text] (one octet per character). */
        fun utf8String(text: String): Der = ascii(TAG_UTF8_STRING, text)

        /**
         * The RFC 5280 `Time` for the whole-second [instant]: UTCTime through 2049, GeneralizedTime from
         * 2050. The calendar comes from [Instant.toString]; only the DER framing is done here.
         */
        fun time(instant: Instant): Der {
            val iso = instant.toString()
            val shaped =
                iso.length == ISO_SECONDS_LENGTH &&
                    iso[4] == '-' &&
                    iso[7] == '-' &&
                    iso[10] == 'T' &&
                    iso[13] == ':' &&
                    iso[16] == ':' &&
                    iso[19] == 'Z' &&
                    iso.substring(0, 4).all { it in '0'..'9' }
            if (!shaped) throw PeerCertificateException(PeerCertificateFailure.TimeNotEncodable(instant))
            val year = iso.substring(0, 4).toInt()
            if (year < FIRST_UTC_TIME_YEAR) throw PeerCertificateException(PeerCertificateFailure.TimeNotEncodable(instant))
            val digits = iso.filter { it in '0'..'9' } // YYYYMMDDhhmmss
            return if (year < FIRST_GENERALIZED_TIME_YEAR) {
                ascii(TAG_UTC_TIME, digits.substring(2) + "Z")
            } else {
                ascii(TAG_GENERALIZED_TIME, digits + "Z")
            }
        }

        /** A BIT STRING with no unused bits whose content is [bytes] (not consumed). */
        fun bitString(bytes: ReadBuffer): Der =
            Primitive(TAG_BIT_STRING, 1 + bytes.remaining()) { dest ->
                dest.writeByte(0)
                copy(bytes, bytes.position(), bytes.limit(), dest)
            }

        /** An OCTET STRING whose content is [bytes] (not consumed). */
        fun octetString(bytes: ReadBuffer): Der =
            Primitive(TAG_OCTET_STRING, bytes.remaining()) { dest -> copy(bytes, bytes.position(), bytes.limit(), dest) }

        /** An OCTET STRING whose content is the DER encoding of [child]. */
        fun octetString(child: Der): Der = Constructed(TAG_OCTET_STRING, arrayOf(child))

        /** An already-encoded TLV, written verbatim (not consumed). */
        fun encoded(tlv: ReadBuffer): Der =
            object : Der() {
                override val encodedLength = tlv.remaining()

                override fun writeTo(dest: WriteBuffer) = copy(tlv, tlv.position(), tlv.limit(), dest)
            }

        private fun ascii(
            tag: Int,
            text: String,
        ): Der = Primitive(tag, text.length) { dest -> text.forEach { dest.writeByte(it.code.toByte()) } }
    }
}

/** A TLV whose content is its [children], encoded back to back. Also frames an OCTET STRING around DER. */
private class Constructed(
    tag: Int,
    private val children: Array<out Der>,
) : Der() {
    private val header = Header(tag, children.sumOf { it.encodedLength })
    override val encodedLength = header.encodedLength

    override fun writeTo(dest: WriteBuffer) {
        header.writeTo(dest)
        children.forEach { it.writeTo(dest) }
    }
}

private class Primitive(
    tag: Int,
    contentLength: Int,
    private val content: (WriteBuffer) -> Unit,
) : Der() {
    private val header = Header(tag, contentLength)
    override val encodedLength = header.encodedLength

    override fun writeTo(dest: WriteBuffer) {
        header.writeTo(dest)
        content(dest)
    }
}

/** Identifier octet plus the minimal definite-length octets for [contentLength]. */
private class Header(
    private val tag: Int,
    private val contentLength: Int,
) {
    private val longFormOctets: Int =
        if (contentLength < SHORT_LENGTH_LIMIT) 0 else (32 - contentLength.countLeadingZeroBits() + 7) / 8
    val encodedLength: Int = 2 + longFormOctets + contentLength

    fun writeTo(dest: WriteBuffer) {
        dest.writeByte(tag.toByte())
        if (longFormOctets == 0) {
            dest.writeByte(contentLength.toByte())
        } else {
            dest.writeByte((LONG_LENGTH_FLAG or longFormOctets).toByte())
            for (i in longFormOctets - 1 downTo 0) dest.writeByte((contentLength ushr (8 * i)).toByte())
        }
    }
}

private fun base128Length(value: Long): Int {
    var octets = 1
    var rest = value ushr OID_PAYLOAD_BITS
    while (rest != 0L) {
        octets++
        rest = rest ushr OID_PAYLOAD_BITS
    }
    return octets
}

private fun writeBase128(
    value: Long,
    dest: WriteBuffer,
) {
    for (i in base128Length(value) - 1 downTo 0) {
        val group = ((value ushr (OID_PAYLOAD_BITS * i)) and OID_PAYLOAD_MASK).toInt()
        dest.writeByte((if (i > 0) group or OID_CONTINUES else group).toByte())
    }
}

private fun copy(
    source: ReadBuffer,
    start: Int,
    end: Int,
    dest: WriteBuffer,
) {
    for (i in start until end) dest.writeByte(source[i])
}

private const val PEM_LINE_CHARS = 64
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val SIX_BITS = 0x3F

/**
 * RFC 7468 PEM of [der]'s remaining bytes (not consumed) under [label], e.g. `CERTIFICATE`, into a new
 * buffer from [factory], returned ready to read.
 */
internal fun pem(
    label: String,
    der: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    val begin = "-----BEGIN $label-----\n"
    val end = "-----END $label-----\n"
    val base64Chars = (der.remaining() + 2) / 3 * 4
    val lines = (base64Chars + PEM_LINE_CHARS - 1) / PEM_LINE_CHARS
    val out = factory.allocate(begin.length + base64Chars + lines + end.length)
    begin.forEach { out.writeByte(it.code.toByte()) }
    var column = 0

    fun put(c: Char) {
        out.writeByte(c.code.toByte())
        if (++column == PEM_LINE_CHARS) {
            out.writeByte('\n'.code.toByte())
            column = 0
        }
    }
    var i = der.position()
    val limit = der.limit()
    while (i < limit) {
        val b0 = der[i].toInt() and BYTE_MASK
        val b1 = if (i + 1 < limit) der[i + 1].toInt() and BYTE_MASK else 0
        val b2 = if (i + 2 < limit) der[i + 2].toInt() and BYTE_MASK else 0
        put(BASE64_ALPHABET[(b0 shr 2) and SIX_BITS])
        put(BASE64_ALPHABET[((b0 shl 4) or (b1 shr 4)) and SIX_BITS])
        put(if (i + 1 < limit) BASE64_ALPHABET[((b1 shl 2) or (b2 shr 6)) and SIX_BITS] else '=')
        put(if (i + 2 < limit) BASE64_ALPHABET[b2 and SIX_BITS] else '=')
        i += 3
    }
    if (column != 0) out.writeByte('\n'.code.toByte())
    end.forEach { out.writeByte(it.code.toByte()) }
    out.resetForRead()
    return out
}
