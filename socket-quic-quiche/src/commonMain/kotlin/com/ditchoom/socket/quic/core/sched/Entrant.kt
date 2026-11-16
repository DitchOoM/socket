package com.ditchoom.socket.quic.core.sched

import kotlin.jvm.JvmInline

/**
 * Who may be inside a connection, packed into the low 62 bits of an [OwnerWord].
 *
 * A loop turn is the loop's id (12 bits) and its generation (48 bits); a caller is a token (60 bits).
 */
@JvmInline
internal value class Entrant private constructor(
    val code: Long,
) {
    companion object {
        private const val CALLER_KIND = 1L shl 60
        private const val WHO_MASK = (1L shl 60) - 1
        private const val GENERATION_BITS = 48
        private const val GENERATION_MASK = (1L shl GENERATION_BITS) - 1
        private const val LOOP_ID_MASK = (1L shl 12) - 1
        private const val ENTRANT_MASK = (1L shl 62) - 1

        fun loopTurn(
            loop: Int,
            generation: Long,
        ): Entrant = Entrant(((loop.toLong() and LOOP_ID_MASK) shl GENERATION_BITS) or (generation and GENERATION_MASK))

        fun caller(token: Long): Entrant = Entrant(CALLER_KIND or (token and WHO_MASK))

        fun decode(word: Long): Entrant = Entrant(word and ENTRANT_MASK)
    }
}
