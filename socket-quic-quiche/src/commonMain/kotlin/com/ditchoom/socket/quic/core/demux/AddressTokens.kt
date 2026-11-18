package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.sha256Into
import kotlin.random.Random

/** What [AddressTokens.check] concluded about the token an Initial carried. */
internal sealed interface TokenCheck {
    /** A Retry token minted by this server for this peer address and this new connection id, not yet expired. */
    class Valid(
        /** Where the original destination id sits in the token, and how long it is. */
        val originalDcidOffset: Int,
        val originalDcidLength: Int,
    ) : TokenCheck

    /** A NEW_TOKEN token this server minted for this client IP address, not yet expired: the address is validated. */
    data object Proven : TokenCheck

    /**
     * Not a Retry token, and not a valid NEW_TOKEN token of this server for this IP address: another key, another
     * address, expired, or unknown. RFC 9000 §8.1.3: the server proceeds as if the Initial carried none.
     */
    data object Unproven : TokenCheck

    /** A Retry token too short or too long to be one of ours, or naming an impossible id length. */
    data object Malformed : TokenCheck

    /** A Retry token whose tag does not match: another key, peer address or connection id, or tampering. */
    data object Forged : TokenCheck

    /** A Retry token, ours and authentic, but past its lifetime. */
    data object Expired : TokenCheck
}

/**
 * Stateless address-validation tokens under a key drawn when the server was built; the server keeps nothing per
 * token. The first byte says which kind a token is (RFC 9000 §8.1.1), and is covered by the tag. Loop thread only:
 * the scratch buffers are shared across calls.
 *
 * Retry: `R | expiry (8) | original dcid length (1) | original dcid | tag (16)`, the tag also over the new connection
 * id the client must use and the peer's address and port.
 *
 * NEW_TOKEN: `N | nonce (16) | sealed expiry (8) | tag (16)`, the tag also over the client's IP address, not its port
 * (RFC 9000 §8.1.4). The random nonce makes each token unique, and the expiry is sealed under a pad derived from it,
 * so nothing in the clear links the token to the connection that issued it (§8.1.3).
 */
internal class AddressTokens(
    factory: BufferFactory,
    private val random: Random = connectionIdRandom,
    private val lifetimeNanos: Long,
    private val futureLifetimeNanos: Long = lifetimeNanos,
) {
    private val key = LongArray(KEY_WORDS) { random.nextLong() }
    private val work = factory.allocate(WORK_BYTES)
    private val inner = freeingIfRefused(work) { factory.allocate(DIGEST_BYTES) }
    private val tag = freeingIfRefused(work, inner) { factory.allocate(DIGEST_BYTES) }

    /**
     * Writes a Retry token for [original] (the destination id the client's first Initial used), to be redeemed from
     * [peer] on a connection whose id is the two longs [newCid0] and [newCid1], into [out] at position 0. Its length.
     */
    fun mint(
        out: PlatformBuffer,
        nowNanos: Long,
        original: CidRead,
        newCid0: Long,
        newCid1: Long,
        peer: PathKey,
    ): Int {
        out.resetForWrite()
        out.writeByte(RETRY)
        out.writeLong(nowNanos + lifetimeNanos)
        out.writeByte(original.length.toByte())
        for (i in 0 until original.length) out.writeByte(original.byteAt(i).toByte())
        val prefix = out.position()
        authenticate(out, prefix, newCid0, newCid1, peer.hi, peer.lo, addressWord(peer, peer.port))
        return sealTag(out, prefix)
    }

    /** Writes a NEW_TOKEN token for the client at [peer]'s IP address into [out] at position 0. Its length. */
    fun mintFuture(
        out: PlatformBuffer,
        nowNanos: Long,
        peer: PathKey,
    ): Int {
        out.resetForWrite()
        out.writeByte(FUTURE)
        val nonce0 = random.nextLong()
        val nonce1 = random.nextLong()
        out.writeLong(nonce0)
        out.writeLong(nonce1)
        out.writeLong((nowNanos + futureLifetimeNanos) xor pad(nonce0, nonce1))
        val prefix = out.position()
        authenticate(out, prefix, 0L, 0L, peer.hi, peer.lo, addressWord(peer, port = 0))
        return sealTag(out, prefix)
    }

    /** Checks the [length]-byte token in [token] against the connection id [dcid] and address [peer] it came from. */
    fun check(
        token: PlatformBuffer,
        length: Int,
        nowNanos: Long,
        dcid: CidRead,
        peer: PathKey,
    ): TokenCheck =
        when (if (length > 0) token[0] else NONE) {
            RETRY -> checkRetry(token, length, nowNanos, dcid, peer)
            FUTURE -> checkFuture(token, length, nowNanos, peer)
            else -> TokenCheck.Unproven
        }

    fun free() {
        work.freeNativeMemory()
        inner.freeNativeMemory()
        tag.freeNativeMemory()
    }

    private fun checkRetry(
        token: PlatformBuffer,
        length: Int,
        nowNanos: Long,
        dcid: CidRead,
        peer: PathKey,
    ): TokenCheck {
        if (length < RETRY_FIXED || dcid.length != QUIC_ISSUED_CONN_ID_LEN) return TokenCheck.Malformed
        val originalLength = token[KIND_BYTES + EXPIRY_BYTES].toInt() and 0xFF
        if (originalLength > CidRead.MAX_CID_LENGTH || length != RETRY_FIXED + originalLength) return TokenCheck.Malformed
        val prefix = KIND_BYTES + EXPIRY_BYTES + 1 + originalLength
        authenticate(token, prefix, dcid.c0, dcid.c1, peer.hi, peer.lo, addressWord(peer, peer.port))
        if (!tagMatches(token, prefix)) return TokenCheck.Forged
        if (token.getLong(KIND_BYTES) < nowNanos) return TokenCheck.Expired
        return TokenCheck.Valid(KIND_BYTES + EXPIRY_BYTES + 1, originalLength)
    }

    private fun checkFuture(
        token: PlatformBuffer,
        length: Int,
        nowNanos: Long,
        peer: PathKey,
    ): TokenCheck {
        if (length != FUTURE_BYTES) return TokenCheck.Unproven
        authenticate(token, FUTURE_PREFIX, 0L, 0L, peer.hi, peer.lo, addressWord(peer, port = 0))
        if (!tagMatches(token, FUTURE_PREFIX)) return TokenCheck.Unproven
        val nonce0 = token.getLong(KIND_BYTES)
        val nonce1 = token.getLong(KIND_BYTES + WORD)
        val expiry = token.getLong(KIND_BYTES + NONCE_BYTES) xor pad(nonce0, nonce1)
        return if (expiry < nowNanos) TokenCheck.Unproven else TokenCheck.Proven
    }

    /** The first word of a keyed digest of the nonce: what a NEW_TOKEN token's expiry is sealed under. */
    private fun pad(
        nonce0: Long,
        nonce1: Long,
    ): Long {
        authenticate(tag, 0, nonce0, nonce1, 0L, 0L, PAD_DOMAIN)
        return tag.getLong(0)
    }

    private fun sealTag(
        out: PlatformBuffer,
        prefix: Int,
    ): Int {
        out.position(prefix)
        out.writeLong(tag.getLong(0))
        out.writeLong(tag.getLong(WORD))
        return out.position()
    }

    private fun tagMatches(
        token: PlatformBuffer,
        prefix: Int,
    ): Boolean = ((token.getLong(prefix) xor tag.getLong(0)) or (token.getLong(prefix + WORD) xor tag.getLong(WORD))) == 0L

    private fun addressWord(
        peer: PathKey,
        port: Int,
    ): Long = (peer.family.toLong() shl PORT_BITS) or port.toLong()

    /** Leaves HMAC-SHA256 of [message]'s first [prefix] bytes and the five words after it in [tag]. */
    private fun authenticate(
        message: PlatformBuffer,
        prefix: Int,
        w0: Long,
        w1: Long,
        hi: Long,
        lo: Long,
        last: Long,
    ) {
        work.resetForWrite()
        for (i in 0 until BLOCK_WORDS) work.writeLong(keyWord(i) xor IPAD)
        for (i in 0 until prefix) work.writeByte(message[i])
        work.writeLong(w0)
        work.writeLong(w1)
        work.writeLong(hi)
        work.writeLong(lo)
        work.writeLong(last)
        work.resetForRead()
        inner.resetForWrite()
        sha256Into(work, inner)
        work.resetForWrite()
        for (i in 0 until BLOCK_WORDS) work.writeLong(keyWord(i) xor OPAD)
        for (i in 0 until DIGEST_BYTES step WORD) work.writeLong(inner.getLong(i))
        work.resetForRead()
        tag.resetForWrite()
        sha256Into(work, tag)
    }

    private fun keyWord(index: Int): Long = if (index < KEY_WORDS) key[index] else 0L

    private companion object {
        const val NONE: Byte = 0
        const val RETRY: Byte = 0x52
        const val FUTURE: Byte = 0x4E
        const val WORD = 8
        const val KEY_WORDS = 4
        const val BLOCK_WORDS = 8
        const val DIGEST_BYTES = 32
        const val TAG_BYTES = 16
        const val KIND_BYTES = 1
        const val EXPIRY_BYTES = 8
        const val NONCE_BYTES = 16
        const val RETRY_FIXED = KIND_BYTES + EXPIRY_BYTES + 1 + TAG_BYTES
        const val FUTURE_PREFIX = KIND_BYTES + NONCE_BYTES + EXPIRY_BYTES
        const val FUTURE_BYTES = FUTURE_PREFIX + TAG_BYTES
        const val PORT_BITS = 32
        const val PAD_DOMAIN = 0x4E45575F544F4B4EL
        const val WORK_BYTES = 256
        const val IPAD = 0x3636363636363636L
        const val OPAD = 0x5C5C5C5C5C5C5C5CL
    }
}

/** How far an allocation of [freeingIfRefused] got. */
private enum class Allocating { Running, Made }

/** Allocates with [allocate]; if it does not return, the buffers [made] before it are freed. */
private inline fun freeingIfRefused(
    vararg made: PlatformBuffer,
    allocate: () -> PlatformBuffer,
): PlatformBuffer {
    var step = Allocating.Running
    try {
        return allocate().also { step = Allocating.Made }
    } finally {
        when (step) {
            Allocating.Made -> Unit
            Allocating.Running -> made.forEach { it.freeNativeMemory() }
        }
    }
}
