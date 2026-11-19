package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.SocketRefusal

/**
 * The 64-bit result of a `qnet` send or receive, as `qnet.h` packs it: datagrams in the low 32 bits, the refusal that
 * stopped the batch in bits 32..47, flags above. [REPLACED] and [CLOSED] are results this side makes when the socket's
 * descriptor was swapped or closed under the call.
 */
internal object NativeResult {
    const val COUNT_MASK = 0xFFFF_FFFFL
    const val CODE_SHIFT = 32
    const val CODE_MASK = 0xFFFFL

    /** `QN_RESULT_SEGMENT_REFUSED`: the device refused `UDP_SEGMENT`, the batch was sent without it. */
    const val SEGMENT_REFUSED = 1L shl 48

    /** `QN_RESULT_BACKLOG`: whole coalesced messages wait inside `qnet` after this read. */
    const val BACKLOG = 1L shl 49
    private const val REPLACED_CODE = 0xFFFE
    private const val CLOSED_CODE = 0xFFFD
    const val REPLACED = REPLACED_CODE.toLong() shl CODE_SHIFT
    const val CLOSED = CLOSED_CODE.toLong() shl CODE_SHIFT

    fun count(result: Long): Int = (result and COUNT_MASK).toInt()

    fun code(result: Long): Int = ((result ushr CODE_SHIFT) and CODE_MASK).toInt()

    fun errorOf(code: Int): UdpError =
        when (code) {
            REPLACED_CODE -> UdpError.Replaced
            CLOSED_CODE -> UdpError.SocketClosed
            else -> udpErrorOf(SocketRefusal.of(code))
        }
}
