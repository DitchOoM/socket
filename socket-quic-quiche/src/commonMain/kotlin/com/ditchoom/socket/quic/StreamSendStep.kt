package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * What [QuicheApi.connStreamSendStep] answers, packed into one `Long` so a write allocates nothing. The top two bits
 * tell the kind: bytes quiche took (the low 32 bits), the peer stopped the stream or reset it (its application error
 * code in the low 62 bits), or another quiche error code ([QUICHE_ERR_DONE_CODE] is "can take nothing now").
 */
@JvmInline
value class StreamSendStep private constructor(
    private val raw: Long,
) {
    /**
     * Dispatches on the kind: quiche [took] that many bytes, is [full], or [refused] the write with a quiche error code
     * and the peer's application error code (0 when the peer sent none).
     */
    inline fun <R> fold(
        took: (bytes: Int) -> R,
        full: () -> R,
        refused: (quicheCode: Int, peer: QuicAppErrorCode) -> R,
    ): R {
        val packed = packed()
        return when (packed ushr KIND_SHIFT) {
            KIND_TAKEN -> took(packed.toInt())
            KIND_STOPPED -> refused(STOPPED_CODE, QuicAppErrorCode(packed and PAYLOAD))
            KIND_RESET -> refused(RESET_CODE, QuicAppErrorCode(packed and PAYLOAD))
            else -> if (packed.toInt() == QUICHE_ERR_DONE_CODE) full() else refused(packed.toInt(), QuicAppErrorCode(0L))
        }
    }

    @PublishedApi
    internal fun packed(): Long = raw

    companion object {
        const val QUICHE_ERR_DONE_CODE = -1

        @PublishedApi internal const val STOPPED_CODE = -15

        @PublishedApi internal const val RESET_CODE = -16

        @PublishedApi internal const val KIND_SHIFT = 62

        @PublishedApi internal const val KIND_TAKEN = 0L

        @PublishedApi internal const val KIND_STOPPED = 1L

        @PublishedApi internal const val KIND_RESET = 2L

        private const val KIND_ERROR = 3L

        @PublishedApi internal const val PAYLOAD = (1L shl 62) - 1

        private const val INT_BITS = 0xFFFFFFFFL

        /** quiche took [bytes], which is not negative. */
        fun taken(bytes: Int): StreamSendStep = StreamSendStep(bytes.toLong())

        /** The peer stopped the stream with [code]. */
        fun stopped(code: QuicAppErrorCode): StreamSendStep = StreamSendStep((KIND_STOPPED shl KIND_SHIFT) or (code.value and PAYLOAD))

        /** The peer reset the stream with [code]. */
        fun reset(code: QuicAppErrorCode): StreamSendStep = StreamSendStep((KIND_RESET shl KIND_SHIFT) or (code.value and PAYLOAD))

        /** quiche's raw answer [result], which is a byte count or a negative error code; [peer] reads the peer's code. */
        inline fun of(
            result: Int,
            peer: () -> QuicAppErrorCode,
        ): StreamSendStep =
            when {
                result >= 0 -> taken(result)
                result == STOPPED_CODE -> stopped(peer())
                result == RESET_CODE -> reset(peer())
                else -> error(result)
            }

        /** A quiche error code, which is negative. */
        fun error(code: Int): StreamSendStep = StreamSendStep((KIND_ERROR shl KIND_SHIFT) or (code.toLong() and INT_BITS))

        /** The step a [StreamSendResult] stands for; a peer code the backend did not report is 0. */
        fun of(result: StreamSendResult): StreamSendStep = of(result.result) { result.errorCode ?: QuicAppErrorCode(0L) }
    }
}
