package com.ditchoom.socket.quic

/**
 * What a connect came to: the connection, or for the block form what the block made of it, with how the race over
 * the peer's candidates went; or why the connect was refused before any socket opened. A handshake that fails is the
 * connection's end, raised as [QuicCloseException].
 */
sealed interface QuicConnect<out T> {
    /** The handshake completed with the candidate [race] names; [value] is the connection, or the block's value. */
    class Connected<out T>(
        val value: T,
        val race: QuicCandidateRace,
    ) : QuicConnect<T> {
        override fun toString(): String = "Connected($value, $race)"
    }

    /** Nothing was opened: [refusal] says which option value cannot be honoured here. */
    class Refused(
        val refusal: QuicRefusal,
    ) : QuicConnect<Nothing> {
        override fun toString(): String = "Refused($refusal)"
    }
}

/** The connection or the block's value, or what [onRefused] makes of the refusal; [onRefused] may leave instead. */
inline fun <R, T : R> QuicConnect<T>.getOrElse(onRefused: (QuicRefusal) -> R): R =
    when (this) {
        is QuicConnect.Connected -> value
        is QuicConnect.Refused -> onRefused(refusal)
    }

/** What a serve came to: the listener, or for the block form the block's value; or why it was refused before any socket opened. */
sealed interface QuicServe<out T> {
    class Serving<out T>(
        val value: T,
    ) : QuicServe<T> {
        override fun toString(): String = "Serving($value)"
    }

    /** Nothing was opened: [refusal] says which option value cannot be honoured here. */
    class Refused(
        val refusal: QuicRefusal,
    ) : QuicServe<Nothing> {
        override fun toString(): String = "Refused($refusal)"
    }
}

/** The listener or the block's value, or what [onRefused] makes of the refusal; [onRefused] may leave instead. */
inline fun <R, T : R> QuicServe<T>.getOrElse(onRefused: (QuicRefusal) -> R): R =
    when (this) {
        is QuicServe.Serving -> value
        is QuicServe.Refused -> onRefused(refusal)
    }

/**
 * A bound QUIC server. Every call suspends, never blocks a thread, and is safe from a main thread.
 *
 * Its connections come either through [accept], one at a time, or through [handleConnections], which runs a block per
 * connection; use one of the two.
 */
interface QuicListener {
    /** The UDP port the listener is bound to. */
    val port: Int

    /** Waits for the next connection whose handshake completed, or whose client's 0-RTT was accepted. */
    suspend fun accept(): QuicAccept

    /**
     * Runs [handler] once for every connection, placed by the library, and returns once the listener closed and every
     * handler returned. When [handler] returns or throws, its connection's open plain-form streams are reset and the
     * connection closes: gracefully after a return, at once after a throw.
     */
    suspend fun handleConnections(handler: suspend (QuicConnection) -> Unit)

    /** Stops accepting and closes every connection; returns once the socket is closed. */
    suspend fun close()
}

/** What a [QuicListener.accept] came to. */
sealed interface QuicAccept {
    class Accepted(
        val connection: QuicConnection,
    ) : QuicAccept

    /** The listener closed: no connection comes any more. */
    data object Closed : QuicAccept
}
