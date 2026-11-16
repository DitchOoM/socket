package com.ditchoom.socket.quic.core.stream

import kotlin.jvm.JvmInline

/**
 * How many bytes a stream's next read asks quiche for, which is the size of the buffer the read allocates: it starts at
 * [Initial], doubles up to [Max] while reads fill it and halves down to [Initial] while they use a quarter or less of
 * it, so a stream of small messages holds small buffers and a bulk stream converges on [Max].
 */
@JvmInline
internal value class ReadChunk private constructor(
    val bytes: Int,
) {
    /** The chunk for the read after one that [asked] for that many bytes and got [read] of them. */
    fun after(
        asked: Int,
        read: Int,
    ): ReadChunk =
        when {
            read >= asked -> ReadChunk(minOf(asked * 2, MAX_BYTES))
            read * SHRINK_BELOW <= asked -> ReadChunk(maxOf(asked / 2, INITIAL_BYTES))
            else -> this
        }

    companion object {
        private const val INITIAL_BYTES = 2_048
        const val MAX_BYTES = 65_536
        private const val SHRINK_BELOW = 4

        val Initial = ReadChunk(INITIAL_BYTES)
        val Max = ReadChunk(MAX_BYTES)
    }
}
