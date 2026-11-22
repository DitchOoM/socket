package com.ditchoom.socket.http3.codec

import kotlin.jvm.JvmInline

/**
 * The identifier of an HTTP/3 stream: the request stream's id that RFC 9204 §4.4 names in Section
 * Acknowledgment and Stream Cancellation, and that RFC 9114 error reporting may cite. A transport-neutral
 * wrapper over the id, so the codec layer names no transport type.
 *
 * The id is a variable-length integer, so it lies in `0..2^62 - 1`. The constructor is internal: values come
 * only from a transport's stream id (already in range) or from [QpackPrefixedInteger] (bounded to the same
 * range, with a typed decode failure beyond it), so the `init` check guards a local bug, never peer input.
 *
 * Bit 0 is the initiator (0 = client) and bit 1 the directionality (0 = bidirectional), the encoding of
 * RFC 9000 §2.1 that HTTP/3 inherits.
 */
@JvmInline
value class Http3StreamId internal constructor(
    val id: Long,
) : Comparable<Http3StreamId> {
    init {
        require(id in 0..QpackPrefixedInteger.MAX_VALUE)
    }

    val isUnidirectional: Boolean get() = id and 0x2L != 0L

    override fun compareTo(other: Http3StreamId): Int = id.compareTo(other.id)

    override fun toString(): String = "Http3StreamId($id)"
}
