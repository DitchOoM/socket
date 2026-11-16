package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey

/**
 * Where each field of the socket spec block sits, as `qnet.h` lays it out. [FINGERPRINT] is compared with the one the
 * loaded `qnet` reports before any socket is opened, so a renumbering on either side fails loudly instead of reading
 * another field's bytes. Socket options are not fields: each is a call on the open descriptor.
 */
internal object SpecLayout {
    const val BIND = 0
    const val PEER = 24
    const val RCVBUF = 48
    const val SNDBUF = 52
    const val GRANTED_RCV = 56
    const val GRANTED_SND = 60
    const val LOCAL = 64
    const val BYTES = 88

    /** `QN_SPEC_LAYOUT`: the block's size and offsets folded as `h * 31 + offset`, in the order above. */
    val FINGERPRINT: Int =
        intArrayOf(BYTES, BIND, PEER, RCVBUF, SNDBUF, GRANTED_RCV, GRANTED_SND, LOCAL).fold(0) { h, v -> h * 31 + v }
}

/** The loaded `qnet`'s [reported] fingerprint is not the [expected] one: a stale or mismatched native library. */
internal class SpecLayoutMismatch(
    val expected: Int,
    val reported: Int,
)

/** Writes [key] as a `qnet` address record (family, port, hi, lo) at [offset]. */
internal fun putRecord(
    bytes: NativeBytes,
    offset: Int,
    key: PathKey,
) {
    bytes.putInt(offset, key.family)
    bytes.putInt(offset + 4, key.port)
    bytes.putLong(offset + 8, key.hi)
    bytes.putLong(offset + 16, key.lo)
}

/** Reads the `qnet` address record at [offset]. */
internal fun recordAt(
    bytes: NativeBytes,
    offset: Int,
): PathKey = PathKey(bytes.intAt(offset), bytes.intAt(offset + 4), bytes.longAt(offset + 8), bytes.longAt(offset + 16))

internal fun copyRecord(
    bytes: NativeBytes,
    from: Int,
    to: Int,
) = bytes.copyFrom(bytes, from, to, RECORD_BYTES)

private const val RECORD_BYTES = 24
