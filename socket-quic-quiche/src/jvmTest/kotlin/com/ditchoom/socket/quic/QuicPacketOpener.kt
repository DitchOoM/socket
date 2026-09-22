package com.ditchoom.socket.quic

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 9001 packet protection, written against the RFC with javax.crypto and nothing of quiche's: enough
 * to open the QUIC v1 packets a replay trace recorded, given the secrets an NSS key log holds. It is
 * the offline reader a walk's `traces/` + key logs are for, kept to what a test needs.
 */
internal object QuicPacketOpener {
    /** The TLS 1.3 cipher suites QUIC v1 uses (RFC 9001 §5.3). */
    enum class Suite(
        val id: Int,
        val keyLength: Int,
        val hmac: String,
    ) {
        Aes128GcmSha256(0x1301, 16, "HmacSHA256"),
        Aes256GcmSha384(0x1302, 32, "HmacSHA384"),
        ChaCha20Poly1305Sha256(0x1303, 32, "HmacSHA256"),
        ;

        companion object {
            fun of(id: Int): Suite =
                entries.firstOrNull { it.id == id } ?: throw AssertionError("not a QUIC v1 cipher suite: 0x${id.toString(16)}")
        }
    }

    class PacketKeys(
        val suite: Suite,
        val key: ByteArray,
        val iv: ByteArray,
        val hp: ByteArray,
    )

    /** The keys one direction of one packet number space uses, from its traffic secret (RFC 9001 §5.1). */
    fun keys(
        suite: Suite,
        secret: ByteArray,
    ): PacketKeys =
        PacketKeys(
            suite,
            expandLabel(suite, secret, "quic key", suite.keyLength),
            expandLabel(suite, secret, "quic iv", 12),
            expandLabel(suite, secret, "quic hp", suite.keyLength),
        )

    /** Client and server Initial keys, from the client's original Destination Connection ID (RFC 9001 §5.2). */
    fun initialKeys(dcid: ByteArray): Pair<PacketKeys, PacketKeys> {
        val suite = Suite.Aes128GcmSha256
        val initialSecret = hmac(suite, INITIAL_SALT_V1, dcid)
        return keys(suite, expandLabel(suite, initialSecret, "client in", 32)) to
            keys(suite, expandLabel(suite, initialSecret, "server in", 32))
    }

    /** Where one QUIC packet sits in a datagram. */
    sealed interface Located {
        val start: Int
        val end: Int

        data class Long(
            val type: LongType,
            override val start: Int,
            val pnOffset: Int,
            override val end: Int,
            val dcid: ByteArray,
            val scid: ByteArray,
        ) : Located

        data class Short(
            override val start: Int,
            val pnOffset: Int,
            override val end: Int,
        ) : Located
    }

    enum class LongType { Initial, ZeroRtt, Handshake, Retry }

    /**
     * Every packet coalesced into [datagram] (RFC 9000 §12.2); a short header runs to its end, and zero
     * bytes after the last long header are datagram padding, not a packet (§14.1).
     */
    fun locate(
        datagram: ByteArray,
        shortHeaderDcidLength: Int,
    ): List<Located> {
        val found = mutableListOf<Located>()
        var at = 0
        while (at < datagram.size) {
            if ((at until datagram.size).all { datagram[it].toInt() == 0 }) break
            val first = datagram[at].toInt() and 0xff
            if (first and 0x80 == 0) {
                found += Located.Short(at, at + 1 + shortHeaderDcidLength, datagram.size)
                break
            }
            val type = LongType.entries[(first shr 4) and 0x03]
            var p = at + 5
            val dcid = datagram.copyOfRange(p + 1, p + 1 + datagram[p])
            p += 1 + dcid.size
            val scid = datagram.copyOfRange(p + 1, p + 1 + datagram[p])
            p += 1 + scid.size
            if (type == LongType.Retry) {
                found += Located.Long(type, at, p, datagram.size, dcid, scid)
                break
            }
            if (type == LongType.Initial) {
                val (tokenLength, n) = varint(datagram, p)
                p += n + tokenLength.toInt()
            }
            val (length, n) = varint(datagram, p)
            p += n
            found += Located.Long(type, at, p, p + length.toInt(), dcid, scid)
            at = p + length.toInt()
        }
        return found
    }

    class Opened(
        val packetNumber: Long,
        val payload: ByteArray,
    )

    /**
     * Remove header protection and open the AEAD (RFC 9001 §5.3–5.4). [largestSeen] is the largest packet
     * number already opened in this space and direction, for packet number recovery (RFC 9000 §A.3).
     * Throws [javax.crypto.AEADBadTagException] when [keys] are not the ones that sealed the packet.
     */
    fun open(
        datagram: ByteArray,
        packet: Located,
        keys: PacketKeys,
        largestSeen: Long,
    ): Opened {
        val pnOffset =
            when (packet) {
                is Located.Long -> packet.pnOffset
                is Located.Short -> packet.pnOffset
            }
        val mask = headerProtectionMask(keys, datagram.copyOfRange(pnOffset + 4, pnOffset + 20))
        val header = datagram.copyOfRange(packet.start, pnOffset + 4)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and if (packet is Located.Long) 0x0f else 0x1f)).toByte()
        val pnLength = (header[0].toInt() and 0x03) + 1
        var truncated = 0L
        for (i in 0 until pnLength) {
            val at = pnOffset - packet.start + i
            header[at] = (header[at].toInt() xor mask[1 + i].toInt()).toByte()
            truncated = (truncated shl 8) or (header[at].toLong() and 0xff)
        }
        val packetNumber = decodePacketNumber(largestSeen, truncated, pnLength * 8)
        val aad = header.copyOfRange(0, pnOffset - packet.start + pnLength)
        val nonce = keys.iv.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor (packetNumber ushr (8 * i)).toInt()).toByte()
        }
        val sealed = datagram.copyOfRange(pnOffset + pnLength, packet.end)
        return Opened(packetNumber, aeadOpen(keys, nonce, aad, sealed))
    }

    /** The data of the CRYPTO frames at the front of an Initial or Handshake payload, in offset order. */
    fun cryptoData(payload: ByteArray): ByteArray {
        val chunks = sortedMapOf<Long, ByteArray>()
        var p = 0
        while (p < payload.size) {
            when (val type = payload[p].toInt() and 0xff) {
                0x00, 0x01 -> p += 1 // PADDING, PING
                0x02, 0x03 -> { // ACK: largest, delay, range count, first range, ranges, [ECN counts]
                    p += 1
                    repeat(2) { p += varint(payload, p).second }
                    val (ranges, n) = varint(payload, p)
                    p += n
                    p += varint(payload, p).second
                    repeat(ranges.toInt() * 2) { p += varint(payload, p).second }
                    if (type == 0x03) repeat(3) { p += varint(payload, p).second }
                }
                0x06 -> { // CRYPTO: offset, length, data
                    p += 1
                    val (offset, a) = varint(payload, p)
                    p += a
                    val (length, b) = varint(payload, p)
                    p += b
                    chunks[offset] = payload.copyOfRange(p, p + length.toInt())
                    p += length.toInt()
                }
                else -> break
            }
        }
        return chunks.values.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
    }

    /** The 32-byte random of the ClientHello or ServerHello at the start of [handshake] (RFC 8446 §4.1.2). */
    fun helloRandom(handshake: ByteArray): ByteArray = handshake.copyOfRange(6, 38)

    /** The cipher suite a ServerHello at the start of [handshake] selected (RFC 8446 §4.1.3). */
    fun serverHelloSuite(handshake: ByteArray): Suite {
        check(handshake[0].toInt() == 2) { "not a ServerHello: handshake type ${handshake[0]}" }
        val suiteAt = 38 + 1 + handshake[38]
        return Suite.of(((handshake[suiteAt].toInt() and 0xff) shl 8) or (handshake[suiteAt + 1].toInt() and 0xff))
    }

    private fun headerProtectionMask(
        keys: PacketKeys,
        sample: ByteArray,
    ): ByteArray =
        when (keys.suite) {
            Suite.Aes128GcmSha256, Suite.Aes256GcmSha384 ->
                Cipher.getInstance("AES/ECB/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
                    doFinal(sample)
                }
            Suite.ChaCha20Poly1305Sha256 -> {
                val counter =
                    (sample[0].toInt() and 0xff) or ((sample[1].toInt() and 0xff) shl 8) or
                        ((sample[2].toInt() and 0xff) shl 16) or ((sample[3].toInt() and 0xff) shl 24)
                Cipher.getInstance("ChaCha20").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "ChaCha20"), ChaCha20ParameterSpec(sample.copyOfRange(4, 16), counter))
                    doFinal(ByteArray(5))
                }
            }
        }

    private fun aeadOpen(
        keys: PacketKeys,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray {
        val cipher =
            when (keys.suite) {
                Suite.Aes128GcmSha256, Suite.Aes256GcmSha384 ->
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
                    }
                Suite.ChaCha20Poly1305Sha256 ->
                    Cipher.getInstance("ChaCha20-Poly1305").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "ChaCha20"), IvParameterSpec(nonce))
                    }
            }
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed)
    }

    private fun decodePacketNumber(
        largest: Long,
        truncated: Long,
        bits: Int,
    ): Long {
        val expected = largest + 1
        val window = 1L shl bits
        val half = window / 2
        val candidate = (expected and (window - 1).inv()) or truncated
        return when {
            candidate <= expected - half && candidate < (1L shl 62) - window -> candidate + window
            candidate > expected + half && candidate >= window -> candidate - window
            else -> candidate
        }
    }

    /** HKDF-Expand-Label with an empty context (RFC 8446 §7.1). */
    private fun expandLabel(
        suite: Suite,
        secret: ByteArray,
        label: String,
        length: Int,
    ): ByteArray {
        val fullLabel = "tls13 $label".encodeToByteArray()
        val info = byteArrayOf((length shr 8).toByte(), length.toByte(), fullLabel.size.toByte()) + fullLabel + byteArrayOf(0)
        var block = ByteArray(0)
        var out = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            block = hmac(suite, secret, block + info + byteArrayOf(counter++.toByte()))
            out += block
        }
        return out.copyOf(length)
    }

    private fun hmac(
        suite: Suite,
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance(suite.hmac).run {
            init(SecretKeySpec(key, suite.hmac))
            doFinal(data)
        }

    /** A QUIC variable-length integer at [at] (RFC 9000 §16): its value and encoded length. */
    private fun varint(
        bytes: ByteArray,
        at: Int,
    ): Pair<Long, Int> {
        val length = 1 shl ((bytes[at].toInt() and 0xff) shr 6)
        var value = (bytes[at].toLong() and 0x3f)
        for (i in 1 until length) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
        return value to length
    }

    private val INITIAL_SALT_V1 = "38762cf7f55934b34d179ae6a4c80cadccbb7f0a".hexToBytes()
}

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

internal fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
