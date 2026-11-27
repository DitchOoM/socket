package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * What an open of a stream came to: the stream, or for the block forms what the block made of it, or why no stream was
 * opened. No outcome of an open is an exception.
 *
 * ```
 * val (send, receive) = connection.openStream().getOrElse { why -> return failed(why) }
 * ```
 */
@JvmInline
value class StreamOpen<out S>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val held: Any?,
    ) {
        /** [opened] of the stream or the block's value, or [notOpened] of why no stream was opened. */
        inline fun <T> fold(
            opened: (S) -> T,
            notOpened: (NotOpened) -> T,
        ): T =
            when (val now = held) {
                is NotOpened -> notOpened(now)
                is Kept -> {
                    @Suppress("UNCHECKED_CAST")
                    opened(now.value as S)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    opened(now as S)
                }
            }

        override fun toString(): String = fold({ "Opened($it)" }, { it.toString() })

        /** Why no stream was opened. No stream id was spent, and the connection is as it was. */
        sealed interface NotOpened {
            /** The connection ended, or passed its close point, with [end]. */
            class ConnectionEnded(
                val end: QuicConnectionEnd,
            ) : NotOpened {
                override fun toString(): String = "ConnectionEnded($end)"
            }

            /** A [StreamOpenPolicy.Bounded] wait at the peer's stream limit ran out. A later open may succeed. */
            data object DeadlinePassed : NotOpened
        }

        /** A block's value that is itself a [NotOpened], kept apart from the reason an open made no stream. */
        @PublishedApi
        internal class Kept(
            @PublishedApi internal val value: Any?,
        )

        companion object {
            /** An open that made [stream], or whose block returned it. */
            fun <S> opened(stream: S): StreamOpen<S> = StreamOpen(if (stream is NotOpened) Kept(stream) else stream)

            /** An open that made no stream, for [why]. */
            fun notOpened(why: NotOpened): StreamOpen<Nothing> = StreamOpen(why)
        }
    }

/** The stream or the block's value, or what [onNotOpened] makes of why there is none; [onNotOpened] may leave instead. */
inline fun <R, S : R> StreamOpen<S>.getOrElse(onNotOpened: (StreamOpen.NotOpened) -> R): R = fold({ it }, onNotOpened)
