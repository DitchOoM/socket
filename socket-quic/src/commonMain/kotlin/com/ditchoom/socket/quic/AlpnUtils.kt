package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.Utf8
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.utf8Size
import kotlin.random.Random

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
 * Uses [WriteBuffer.writeText] — no ByteArray intermediaries. The format
 * matches what the generated `AlpnProtocolCodec.encode()` produces.
 *
 * Caller must free the returned buffer.
 */
fun encodeAlpnList(
    protocols: List<String>,
    factory: BufferFactory,
): PlatformBuffer {
    // 1 byte length prefix + the protocol's UTF-8 octets. The prefix must count OCTETS, not UTF-16
    // code units: `proto.length` is only the same number for ASCII, and RFC 7301 IDs are ASCII by
    // convention rather than by anything enforced here. utf8Size() is Utf8.Lenient's size, so the
    // prefix, the allocation, and the bytes writeText emits are all the same count on every platform.
    var totalSize = 0
    for (proto in protocols) {
        totalSize += 1 + proto.utf8Size()
    }
    val buffer = factory.allocate(totalSize)
    for (proto in protocols) {
        buffer.writeByte(proto.utf8Size().toByte())
        buffer.writeText(proto, Utf8.Lenient)
    }
    buffer.resetForRead()
    return buffer
}

/**
 * Generate a connection ID for this endpoint to issue: [QUIC_ISSUED_CONN_ID_LEN] bytes, written as
 * 2 longs — 2 write ops instead of 16, and 128 random bits.
 *
 * Allocated from [factory] for pooling. Caller must free.
 *
 * [random] defaults to the platform default source (unchanged production behaviour); the
 * deterministic simulation harness passes a seeded instance so connection IDs are reproducible
 * per seed (RFC_DETERMINISTIC_SIMULATION.md §3.1).
 */
fun generateScid(
    factory: BufferFactory,
    random: Random = Random.Default,
): PlatformBuffer {
    val buf = factory.allocate(QUIC_ISSUED_CONN_ID_LEN)
    buf.writeLong(random.nextLong())
    buf.writeLong(random.nextLong())
    buf.resetForRead()
    return buf
}

/**
 * QUIC connection-ID max length (RFC 9000 §17.2 — 20 bytes): the most a PEER may choose. Size every
 * buffer that receives a peer-chosen id with this; size nothing this endpoint issues with it — that is
 * [QUIC_ISSUED_CONN_ID_LEN].
 */
const val QUIC_MAX_CONN_ID_LEN = 20

/**
 * Length of every connection ID this endpoint issues — the client's SCID, the server's SCID, and each
 * spare id sent in NEW_CONNECTION_ID. 16 bytes still carries 128 random bits, and saves 4 bytes in
 * every short-header packet the peer sends us.
 *
 * Not the 20-byte maximum because packet analysers follow a migrated path by connection ID, and
 * Wireshark's QUIC dissector links a new 4-tuple to its connection only after a first lookup that
 * assumes a 20-byte DCID has missed. With a 20-byte id that lookup hits, the new path is never
 * linked, and a peer using zero-length ids has its packets on that path left undecryptable — so a
 * capture of a rebind (the interop runner's rebind-port / rebind-addr checks) shows no
 * PATH_CHALLENGE. Ids of any other length from 4 to 19 bytes link correctly.
 */
const val QUIC_ISSUED_CONN_ID_LEN = 16
