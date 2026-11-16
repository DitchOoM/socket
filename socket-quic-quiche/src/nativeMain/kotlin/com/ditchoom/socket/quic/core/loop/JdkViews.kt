package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer

/** Kotlin/Native has no JDK: no value of this type exists. */
internal actual class JdkViews private constructor(
    private val impossible: Nothing,
) {
    fun never(): Nothing = impossible
}

internal actual fun jdkViewsOf(
    stack: NetStack.Jdk,
    buffers: List<ReceiveBuffer>,
): JdkViewsMade = stack.runtime.never()
