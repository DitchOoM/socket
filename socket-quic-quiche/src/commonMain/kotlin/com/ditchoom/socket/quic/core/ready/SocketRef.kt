package com.ditchoom.socket.quic.core.ready

/** The platform's handle to a socket a [Poller] can watch: a channel on the JDK stack, a descriptor on the native one. */
internal expect class SocketRef

/** What a poller watches for a client's sockets: the carrying one's handle, and the second's while one is open. */
internal class SocketRefs(
    val carrying: SocketRef,
    val second: SocketRef,
)

/** What a native poller watches for a socket, read when the socket is added: a reopened socket has a new descriptor. */
internal sealed interface PollHandle {
    class Descriptor(
        val fd: Int,
    ) : PollHandle

    /** Readiness that arrives by completion (Network.framework) or through the JDK stack's selector. */
    data object NotPolled : PollHandle
}

internal expect fun SocketRef.pollHandle(): PollHandle
