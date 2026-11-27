package com.ditchoom.socket.quic

/**
 * What a connect came to: the connection, or for the block form what the block made of it, with how the race over the
 * peer's candidates went; or why there is no connection. No outcome of a connect is an exception.
 */
sealed interface QuicConnect<out T> {
    /**
     * The handshake completed with the candidate [race] names; [value] is the connection, or the block's value. A
     * server that closed right after its handshake hands over a connection already ended: what the handshake settled
     * stays readable, and its first call answers the end.
     */
    class Connected<out T>(
        val value: T,
        val race: QuicCandidateRace,
    ) : QuicConnect<T> {
        override fun toString(): String = "Connected($value, $race)"
    }

    /** Why there is no connection. */
    sealed interface NotConnected : QuicConnect<Nothing>

    /** Nothing was opened: [refusal] says which option value cannot be honoured here. */
    class Refused(
        val refusal: QuicRefusal,
    ) : NotConnected {
        override fun toString(): String = "Refused($refusal)"
    }

    /**
     * No candidate's handshake completed: [end] is how the last one attempted ended. A server certificate the check
     * refused ends as the CONNECTION_CLOSE sent for it; a name with no address, or no route to any, as
     * [QuicConnectionEnd.NoUsablePath]; a 0-RTT the server rejected and could not be replayed, as
     * [QuicConnectionEnd.EarlyDataRejected]. A handshake its own side refused as it completed (a peer transport
     * parameter, say) did not complete.
     */
    class Failed(
        val end: QuicConnectionEnd,
    ) : NotConnected {
        override fun toString(): String = "Failed($end)"
    }
}

/** [connected] of the connection or the block's value, or [notConnected] of why there is none. */
inline fun <T, X> QuicConnect<T>.fold(
    connected: (T) -> X,
    notConnected: (QuicConnect.NotConnected) -> X,
): X =
    when (this) {
        is QuicConnect.Connected -> connected(value)
        is QuicConnect.NotConnected -> notConnected(this)
    }

/** The connection or the block's value, or what [onNotConnected] makes of why there is none; it may leave instead. */
inline fun <R, T : R> QuicConnect<T>.getOrElse(onNotConnected: (QuicConnect.NotConnected) -> R): R = fold({ it }, onNotConnected)

/** What a serve came to: the listener, or for the block form the block's value; or why nothing is served. */
sealed interface QuicServe<out T> {
    class Serving<out T>(
        val value: T,
    ) : QuicServe<T> {
        override fun toString(): String = "Serving($value)"
    }

    /** Why nothing is served. */
    sealed interface NotServing : QuicServe<Nothing>

    /** Nothing was opened: [refusal] says which option value cannot be honoured here. */
    class Refused(
        val refusal: QuicRefusal,
    ) : NotServing {
        override fun toString(): String = "Refused($refusal)"
    }

    /** The listener's socket could not be opened as [fault] says: the address is in use, say, or not this host's. */
    class Failed(
        val fault: QuicSocketFault,
    ) : NotServing {
        override fun toString(): String = "Failed($fault)"
    }
}

/** [serving] of the listener or the block's value, or [notServing] of why nothing is served. */
inline fun <T, X> QuicServe<T>.fold(
    serving: (T) -> X,
    notServing: (QuicServe.NotServing) -> X,
): X =
    when (this) {
        is QuicServe.Serving -> serving(value)
        is QuicServe.NotServing -> notServing(this)
    }

/** The listener or the block's value, or what [onNotServing] makes of why nothing is served; it may leave instead. */
inline fun <R, T : R> QuicServe<T>.getOrElse(onNotServing: (QuicServe.NotServing) -> R): R = fold({ it }, onNotServing)

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
