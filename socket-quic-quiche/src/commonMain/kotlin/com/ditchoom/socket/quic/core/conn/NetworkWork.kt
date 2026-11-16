package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd

/** Tells a connection the network monitor's news; runs inside it, never on the monitor's thread. */
internal class NetworkWork(
    private val change: NetworkChange,
) : ConnWork() {
    override fun run(inside: Inside) = inside.network(change)

    override fun gone(end: ConnEnd) = Unit
}
