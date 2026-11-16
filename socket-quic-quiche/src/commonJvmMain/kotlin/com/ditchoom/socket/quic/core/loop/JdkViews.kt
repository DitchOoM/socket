package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.udp.JdkBacked
import com.ditchoom.socket.quic.core.udp.JdkBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer

internal actual class JdkViews(
    val views: List<JdkBytes>,
)

internal actual fun jdkViewsOf(
    stack: NetStack.Jdk,
    buffers: List<ReceiveBuffer>,
): JdkViewsMade {
    val views = ArrayList<JdkBytes>(buffers.size)
    for (buffer in buffers) {
        when (val view = JdkBytes.of(buffer.bytes)) {
            is JdkBacked.Backed -> views.add(view.bytes)
            is JdkBacked.NotJdkBacked -> return JdkViewsMade.NotJdkMemory
        }
    }
    return JdkViewsMade.Made(JdkViews(views))
}
