package com.ditchoom.socket.quic.core.ready

/** Kotlin/Native has no JDK: no value of this type exists, so no [NetStack.Jdk] does either. */
internal actual class JdkRuntime private constructor(
    private val impossible: Nothing,
) {
    fun never(): Nothing = impossible
}

internal actual fun jdkPoller(stack: NetStack.Jdk): Poller = stack.runtime.never()
