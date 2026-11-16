package com.ditchoom.socket.quic.core.ready

/**
 * A poller and the sockets it can watch are one choice, made once per process.
 *
 * [Jdk] is today's NIO io. The native stack (shim-owned sockets and poller) is a second variant of this type; it is
 * not built yet.
 */
internal sealed interface NetStack {
    class Jdk(
        val scan: SelectedKeys,
    ) : NetStack
}

/** How a selector's ready keys are read: the consumer form allocates nothing; the set form allocates a node per key. */
internal enum class SelectedKeys { ByConsumer, BySet }
