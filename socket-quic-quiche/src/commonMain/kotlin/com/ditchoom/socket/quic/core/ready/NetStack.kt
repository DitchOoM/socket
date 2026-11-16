package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.core.time.WaitResolution

/**
 * A poller and the sockets it can watch are one choice, made once per process.
 *
 * [Native] is `qnet` in the native library that carries quiche: kqueue or epoll over sockets it opened itself, reached
 * through [NetShim] (FFM, JNI or cinterop). [Jdk] is NIO's selector and channels, for a platform where `qnet` has no
 * poller (Windows).
 */
internal sealed interface NetStack {
    class Native(
        val shim: NetShim,
        /** How finely the native poller's timed waits wake. */
        val waits: WaitResolution,
    ) : NetStack

    class Jdk(
        val scan: SelectedKeys,
        /** How finely `Selector.select(timeout)` wakes on this runtime. */
        val waits: WaitResolution,
    ) : NetStack
}

/** How a selector's ready keys are read: the consumer form allocates nothing; the set form allocates a node per key. */
internal enum class SelectedKeys { ByConsumer, BySet }
