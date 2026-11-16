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
    /** Minted by this server for this peer address and this new connection id, and not yet expired. */
    class Valid(
        /** Where the original destination id sits in the token, and how long it is. */
        val originalDcidOffset: Int,
        val originalDcidLength: Int,
    ) : TokenCheck

    /** Too short or too long to be one of ours, or naming an impossible id length. */
    data object Malformed : TokenCheck

    /** The authentication tag does not match: another key, another peer address, another connection id, or tampering. */
    data object Forged : TokenCheck

    /** Ours and authentic, but past its lifetime. */
    data object Expired : TokenCheck
}

/**
 * Stateless address-validation tokens for Retry: expiry, the original destination id and a truncated HMAC-SHA256
 * over those, the new connection id the client must use and the peer's address, under a key drawn when the server
 * was built. The server keeps nothing per token. Loop thread only: the scratch buffers are shared across calls.
 *
 * Layout: `expiry (8) | original dcid length (1) | original dcid | tag (16)`.
 */
internal class AddressTokens(
    factory: BufferFactory,
    random: Random = connectionIdRandom,
    private val lifetimeNanos: Long,
) {
    private val key = LongArray(KEY_WORDS) { random.nextLong() }
    private val work = factory.allocate(WORK_BYTES)
    private val inner = factory.allocate(DIGEST_BYTES)
    private val tag = factory.allocate(DIGEST_BYTES)

    /**
     * Writes a token for [original] (the destination id the client's first Initial used), to be redeemed from [peer]
     * on a connection whose id is the two longs [newCid0] and [newCid1], into [out] at position 0. Returns its length.
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
        out.writeLong(nowNanos + lifetimeNanos)
        out.writeByte(original.length.toByte())
        for (i in 0 until original.length) out.writeByte(original.byteAt(i).toByte())
        val prefix = out.position()
        authenticate(out, prefix, newCid0, newCid1, peer)
        out.position(prefix)
        out.writeLong(tag.getLong(0))
        out.writeLong(tag.getLong(WORD))
        return out.position()
    }

    /** Checks the [length]-byte token in [token] against the connection id [dcid] and address [peer] it came from. */
    fun check(
        token: PlatformBuffer,
        length: Int,
        nowNanos: Long,
        dcid: CidRead,
        peer: PathKey,
    ): TokenCheck {
        if (length < FIXED || dcid.length != QUIC_ISSUED_CONN_ID_LEN) return TokenCheck.Malformed
        val originalLength = token[EXPIRY_BYTES].toInt() and 0xFF
        if (originalLength > CidRead.MAX_CID_LENGTH || length != FIXED + originalLength) return TokenCheck.Malformed
        val prefix = EXPIRY_BYTES + 1 + originalLength
        authenticate(token, prefix, dcid.c0, dcid.c1, peer)
        val genuine = (token.getLong(prefix) xor tag.getLong(0)) or (token.getLong(prefix + WORD) xor tag.getLong(WORD))
        if (genuine != 0L) return TokenCheck.Forged
        if (token.getLong(0) < nowNanos) return TokenCheck.Expired
        return TokenCheck.Valid(EXPIRY_BYTES + 1, originalLength)
    }

    fun free() {
        work.freeNativeMemory()
        inner.freeNativeMemory()
        tag.freeNativeMemory()
    }

    /** Leaves HMAC-SHA256 of [message]'s first [prefix] bytes, the new id and the peer's address in [tag]. */
    private fun authenticate(
        message: PlatformBuffer,
        prefix: Int,
        newCid0: Long,
        newCid1: Long,
        peer: PathKey,
    ) {
        work.resetForWrite()
        for (i in 0 until BLOCK_WORDS) work.writeLong(keyWord(i) xor IPAD)
        for (i in 0 until prefix) work.writeByte(message[i])
        work.writeLong(newCid0)
        work.writeLong(newCid1)
        work.writeLong(peer.hi)
        work.writeLong(peer.lo)
        work.writeLong((peer.family.toLong() shl PORT_BITS) or peer.port.toLong())
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
        const val WORD = 8
        const val KEY_WORDS = 4
        const val BLOCK_WORDS = 8
        const val DIGEST_BYTES = 32
        const val TAG_BYTES = 16
        const val EXPIRY_BYTES = 8
        const val FIXED = EXPIRY_BYTES + 1 + TAG_BYTES
        const val PORT_BITS = 32
        const val WORK_BYTES = 256
        const val IPAD = 0x3636363636363636L
        const val OPAD = 0x5C5C5C5C5C5C5C5CL
    }
}
