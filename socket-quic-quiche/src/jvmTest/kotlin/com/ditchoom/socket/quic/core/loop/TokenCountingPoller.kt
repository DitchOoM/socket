package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.ReadyTokens
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WakeCause
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.time.Nanos

/** A poller that counts what was added and removed, by token. */
internal class TokenCountingPoller : Poller {
    val added = ArrayList<Int>()
    val removed = ArrayList<Int>()

    override fun add(
        socket: SocketRef,
        token: Int,
    ) {
        added += token
    }

    override fun remove(token: Int) {
        removed += token
    }

    override fun interest(
        token: Int,
        write: WriteInterest,
    ) = Unit

    override fun await(
        until: NextDue,
        ready: ReadyTokens,
        now: Nanos,
    ): WakeCause = WakeCause.Woken

    override fun wake() = Unit

    override fun close() = Unit
}
