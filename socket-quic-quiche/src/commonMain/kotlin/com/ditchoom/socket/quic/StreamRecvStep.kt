package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * What [QuicheApi.connStreamRecvStep] answers, packed into one `Long` so a read allocates nothing. The top two bits
 * tell the kind: data (bytes in the low 32 bits, the peer's FIN in bit 32), the connection gone, a peer reset (its
 * application error code in the low 62 bits) or a quiche error code ([QUICHE_ERR_DONE_CODE] is "nothing to read yet").
 */
@JvmInline
value class StreamRecvStep private constructor(
    private val raw: Long,
) {
    /** Dispatches on the kind: [more] bytes were read and the stream goes on, [last] bytes were read with the peer's FIN. */
    inline fun <R> fold(
        more: (bytes: Int) -> R,
        last: (bytes: Int) -> R,
        nothingYet: () -> R,
        gone: () -> R,
        reset: (code: QuicAppErrorCode) -> R,
        error: (code: Int) -> R,
    ): R {
        val packed = packed()
        return when (packed ushr KIND_SHIFT) {
            KIND_DATA -> if (packed and FIN != 0L) last((packed and BYTES).toInt()) else more((packed and BYTES).toInt())
            KIND_GONE -> gone()
            KIND_RESET -> reset(QuicAppErrorCode(packed and PAYLOAD))
            else -> if (packed.toInt() == QUICHE_ERR_DONE_CODE) nothingYet() else error(packed.toInt())
        }
    }

    @PublishedApi
    internal fun packed(): Long = raw

    companion object {
        const val QUICHE_ERR_DONE_CODE = -1

        @PublishedApi internal const val KIND_SHIFT = 62

        @PublishedApi internal const val KIND_DATA = 0L

        @PublishedApi internal const val KIND_GONE = 1L

        @PublishedApi internal const val KIND_RESET = 2L

        @PublishedApi internal const val FIN = 1L shl 32

        @PublishedApi internal const val BYTES = 0xFFFFFFFFL

        @PublishedApi internal const val PAYLOAD = (1L shl 62) - 1

        fun data(
            bytes: Int,
            fin: Boolean,
        ): StreamRecvStep = StreamRecvStep(bytes.toLong() or if (fin) FIN else 0L)

        val Gone = StreamRecvStep(KIND_GONE shl KIND_SHIFT)

        fun reset(code: QuicAppErrorCode): StreamRecvStep = StreamRecvStep((KIND_RESET shl KIND_SHIFT) or (code.value and PAYLOAD))

        /** A quiche error code, which is negative. */
        fun error(code: Int): StreamRecvStep = StreamRecvStep(code.toLong())

        fun of(result: StreamRecvResult): StreamRecvStep =
            when (result) {
                is StreamRecvResult.Data -> data(result.bytesRead, result.fin)
                StreamRecvResult.Done -> error(QUICHE_ERR_DONE_CODE)
                is StreamRecvResult.Reset -> reset(result.applicationErrorCode)
                is StreamRecvResult.Error -> error(result.code)
                StreamRecvResult.ConnectionGone -> Gone
            }
    }
}

/** What [QuicheApi.streamIterNextId] answers: the next stream id of an iterator, or [End]; a primitive, so a step allocates nothing. */
@JvmInline
value class NextStream private constructor(
    private val raw: Long,
) {
    /** [stream] with the next stream's id, or [end] when the iterator is exhausted. */
    inline fun <R> fold(
        end: () -> R,
        stream: (QuicStreamId) -> R,
    ): R = if (id() < 0L) end() else stream(QuicStreamId(id()))

    @PublishedApi
    internal fun id(): Long = raw

    companion object {
        /** Stream ids are never negative. */
        val End = NextStream(-1L)

        fun of(stream: QuicStreamId): NextStream = NextStream(stream.id)
    }
}
