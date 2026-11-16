package com.ditchoom.socket.quic.core.ready

import java.nio.channels.SelectableChannel
import java.util.function.Supplier

/** A socket's channel, read when the poller registers it: a socket reopened on a new network is a new channel. */
internal actual class SocketRef(
    private val current: Supplier<out SelectableChannel>,
) {
    constructor(channel: SelectableChannel) : this(Supplier { channel })

    val channel: SelectableChannel get() = current.get()
}
