package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * ALPN protocol in wire format (RFC 7301).
 *
 * The codec processor generates `AlpnProtocolCodec` per platform target with:
 * - `sizeOf()`: 1 + name.length
 * - `encode()`: writes 1-byte length prefix + ASCII string into WriteBuffer
 * - `decode()`: reads 1-byte length prefix + string from ReadBuffer
 *
 * For commonMain encoding, use [encodeAlpnList] which writes directly via WriteBuffer.
 */
@ProtocolMessage
data class AlpnProtocol(
    @LengthPrefixed(prefix = LengthPrefix.Byte)
    val name: String,
)

/**
 * Encode ALPN protocols directly into a [BufferFactory]-allocated buffer.
 *
 * Uses [WriteBuffer.writeString] — no ByteArray intermediaries. The format
 * matches what the generated `AlpnProtocolCodec.encode()` produces.
 *
 * Caller must free the returned buffer.
 */
fun encodeAlpnList(
    protocols: List<String>,
    factory: BufferFactory,
): PlatformBuffer {
    // Compute size: 1 byte length prefix + N bytes per protocol
    var totalSize = 0
    for (proto in protocols) {
        totalSize += 1 + proto.length
    }
    val buffer = factory.allocate(totalSize)
    for (proto in protocols) {
        buffer.writeByte(proto.length.toByte())
        buffer.writeString(proto, Charset.UTF8)
    }
    buffer.resetForRead()
    return buffer
}

/**
 * Generate a QUIC source connection ID using bulk writes.
 * 20 bytes = 2 longs (16 bytes) + 1 int (4 bytes) — 3 write ops instead of 20.
 *
 * Allocated from [factory] for pooling. Caller must free.
 */
fun generateScid(factory: BufferFactory): PlatformBuffer {
    val buf = factory.allocate(QUIC_MAX_CONN_ID_LEN)
    buf.writeLong(kotlin.random.Random.nextLong())
    buf.writeLong(kotlin.random.Random.nextLong())
    buf.writeInt(kotlin.random.Random.nextInt())
    buf.resetForRead()
    return buf
}

internal const val QUIC_MAX_CONN_ID_LEN = 20
