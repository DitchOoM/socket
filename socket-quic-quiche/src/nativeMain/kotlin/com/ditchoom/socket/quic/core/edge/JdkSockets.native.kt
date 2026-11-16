package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.ready.PollHandle
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer

/** Kotlin/Native has no JDK: no value of this type exists. */
internal actual class JdkSocket private constructor(
    private val impossible: Nothing,
) {
    fun never(): Nothing = impossible
}

internal actual fun openJdkSocket(
    io: LoopIo.Jdk,
    bind: PathKey,
    peer: SocketPeer,
    buffers: SocketBuffers,
): StackSocket.Jdk = io.receiveViews.never()

internal actual fun jdkSocketIo(
    socket: StackSocket.Jdk,
    sends: List<NativeBytes>,
    maxPeersCached: Int,
): SocketIo = socket.socket.never()

internal actual fun descriptorRef(io: NativeIo): SocketRef = SocketRef { PollHandle.Descriptor(io.descriptor()) }
