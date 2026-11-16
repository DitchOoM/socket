package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.core.time.LoopClock
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
        /** Proof of a JDK: only the JVM can make one, so this stack does not exist on Kotlin/Native. */
        val runtime: JdkRuntime,
    ) : NetStack
}

/** A JDK to run NIO on: made only by the JVM. */
internal expect class JdkRuntime

/** The stack of this process, chosen once: a stack that cannot open a socket fails that connect, it never switches. */
internal expect val netStackOfThisRuntime: NetStack

/** The JDK stack's poller: NIO's selector. */
internal expect fun jdkPoller(
    clock: LoopClock,
    stack: NetStack.Jdk,
): Poller

/** How a selector's ready keys are read: the consumer form allocates nothing; the set form allocates a node per key. */
internal enum class SelectedKeys { ByConsumer, BySet }
