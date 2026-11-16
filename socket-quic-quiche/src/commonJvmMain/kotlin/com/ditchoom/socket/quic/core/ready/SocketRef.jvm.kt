package com.ditchoom.socket.quic.core.ready

import java.nio.channels.SelectableChannel
import java.util.function.IntSupplier
import java.util.function.Supplier

/**
 * A socket as its stack's poller watches it, read when the poller registers it: a socket reopened on a new network is
 * a new channel, or a new descriptor.
 */
internal actual class SocketRef private constructor(
    val handle: SocketHandle,
) {
    constructor(current: Supplier<out SelectableChannel>) : this(SocketHandle.Channel(current))

    constructor(channel: SelectableChannel) : this(Supplier { channel })

    constructor(descriptor: IntSupplier) : this(SocketHandle.Descriptor(descriptor))
}

/** The JDK stack watches a channel, the native stack a descriptor; each stack builds only its own. */
internal sealed interface SocketHandle {
    class Channel(
        val current: Supplier<out SelectableChannel>,
    ) : SocketHandle

    class Descriptor(
        val current: IntSupplier,
    ) : SocketHandle
}
