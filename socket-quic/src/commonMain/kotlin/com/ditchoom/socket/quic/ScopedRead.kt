package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * What one read of a [QuicReceiveStream] came to: the value its block made of the bytes, or why there were none. The
 * buffer the block saw is already back with the stream. No outcome of a read is an exception.
 *
 * ```
 * val user = receive.read { parse(it) }.getOrElse { why -> return failed(why) }
 * ```
 *
 * A read that returns a value without waiting allocates nothing for it.
 */
@JvmInline
value class ScopedRead<out R>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val held: Any?,
    ) {
        /** [data] of the block's value, or [noData] of why the read had no bytes. */
        inline fun <T> fold(
            data: (R) -> T,
            noData: (NoData) -> T,
        ): T =
            when (val now = held) {
                is NoData -> noData(now)
                is Kept -> {
                    @Suppress("UNCHECKED_CAST")
                    data(now.value as R)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    data(now as R)
                }
            }

        override fun toString(): String = fold({ "Data($it)" }, { it.toString() })

        /** Why a read gave its block no bytes. */
        sealed interface NoData

        /** The stream gives no more bytes. */
        sealed interface End : NoData {
            /** The peer finished the stream, and every byte it sent was read. */
            data object PeerFinished : End

            /** The peer reset the stream with [code]. */
            class PeerReset(
                val code: QuicAppErrorCode,
            ) : End {
                override fun toString(): String = "PeerReset($code)"
            }

            /**
             * The connection ended with [end] before the peer finished or reset the stream, and every byte the
             * connection held for it was read.
             */
            class ConnectionEnded(
                val end: QuicConnectionEnd,
            ) : End {
                override fun toString(): String = "ConnectionEnded($end)"
            }
        }

        /** The read's deadline passed with no bytes. The stream can still be read. */
        data object DeadlinePassed : NoData

        /** A frame of a typed read broke its rule as [failure] says. The stream was reset and gives no more. */
        class Broken(
            val failure: QuicFrameFailure,
        ) : NoData {
            override fun toString(): String = "Broken($failure)"
        }

        /** A value that is itself a [NoData], kept apart from the reason a read had none. */
        @PublishedApi
        internal class Kept(
            @PublishedApi internal val value: Any?,
        )

        companion object {
            /** A read whose block returned [value]. */
            fun <R> data(value: R): ScopedRead<R> = ScopedRead(if (value is NoData) Kept(value) else value)

            /** A read that had no bytes, for [why]. */
            fun noData(why: NoData): ScopedRead<Nothing> = ScopedRead(why)
        }
    }

/** The block's value, or what [onNoData] makes of why the read had none; [onNoData] may leave instead. */
inline fun <R, T : R> ScopedRead<T>.getOrElse(onNoData: (ScopedRead.NoData) -> R): R = fold({ it }, onNoData)

/** Why a typed read refused a frame. */
sealed interface QuicFrameFailure {
    /** The frame declares [declaredBytes], more than the [capBytes] the stream holds to assemble one. */
    class Oversized(
        val declaredBytes: Long,
        val capBytes: Long,
    ) : QuicFrameFailure

    /** [capBytes] are held and the decoder still cannot tell the frame's size. */
    class Unsized(
        val capBytes: Long,
    ) : QuicFrameFailure

    /** The decoder rejected the frame's bytes with [rejection]. */
    class Rejected(
        val rejection: Exception,
    ) : QuicFrameFailure

    /** The decoder has no way to find a frame's end in a stream. */
    data object Unframed : QuicFrameFailure

    /** The peer finished the stream with [heldBytes] of an incomplete frame. */
    class Truncated(
        val heldBytes: Long,
    ) : QuicFrameFailure
}
