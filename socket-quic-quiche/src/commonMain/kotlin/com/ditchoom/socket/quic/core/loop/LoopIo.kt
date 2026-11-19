package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NetShim
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer

/** What a socket on a loop receives through, by the loop's stack. */
internal sealed interface LoopIo {
    /** One view per receive buffer of the loop, by the buffer's index: what a JDK socket on it reads into. */
    class Jdk(
        val receiveViews: JdkViews,
    ) : LoopIo

    /**
     * The loop's receive records, which a native socket on it hands `qnet` with its buffers' addresses, and its send
     * records, one per lane ([com.ditchoom.socket.quic.core.udp.SendBatch.lane]): a lane sends from one thread at a time,
     * whichever of the loop's sockets it sends to.
     */
    class Native(
        val shim: NetShim,
        val receiveRecords: NativeBytes,
        val sendRecords: List<NativeBytes>,
    ) : LoopIo
}

/** The JDK views of a loop's receive buffers: made only on a JVM. */
internal expect class JdkViews

/** What making a JDK loop's views answered. */
internal sealed interface JdkViewsMade {
    class Made(
        val views: JdkViews,
    ) : JdkViewsMade

    /** The factory's memory is not a JDK buffer a channel can read into. */
    data object NotJdkMemory : JdkViewsMade
}

/** The views a JDK socket on [stack] reads [buffers] through. */
internal expect fun jdkViewsOf(
    stack: NetStack.Jdk,
    buffers: List<ReceiveBuffer>,
): JdkViewsMade
