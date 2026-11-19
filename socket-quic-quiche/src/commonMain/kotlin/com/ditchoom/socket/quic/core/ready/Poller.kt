package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.NextDue

/** The loop's one blocking call and readiness, one implementation per [NetStack] variant. */
internal interface Poller {
    /** Watches [socket] for readability and reports it as [token]. */
    fun add(
        socket: SocketRef,
        token: Int,
    )

    /** Stops watching the socket reported as [token]; the token may be given to another socket afterwards. */
    fun remove(token: Int)

    fun interest(
        token: Int,
        write: WriteInterest,
    )

    /** Blocks until a socket is ready, [wake] is called or [until] passes; fills [ready] with the ready tokens. */
    fun await(
        until: NextDue,
        ready: ReadyTokens,
    ): WakeCause

    /** Any thread. Makes the current or next [await] return. */
    fun wake()

    /** Frees the poller once: a second close, and any call after it, never reaches what was freed. */
    fun close()
}
