package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.udp.DontFragment
import com.ditchoom.socket.quic.core.udp.JdkBacked
import com.ditchoom.socket.quic.core.udp.JdkBytes
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.SendView
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.applyTo
import com.ditchoom.socket.quic.core.udp.forbidFragmentation
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.toInetSocketAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.function.IntSupplier
import java.util.function.Supplier

internal actual class JdkSocket(
    val channel: DatagramChannel,
    /** Whether the channel sends with DF, as it was set at open. */
    val dontFragment: DontFragment = DontFragment.NotOffered,
)

internal actual fun closeJdkSocket(socket: JdkSocket) = socket.channel.close()

internal actual fun openJdkSocket(
    io: LoopIo.Jdk,
    bind: PathKey,
    peer: SocketPeer,
    buffers: SocketBuffers,
): StackSocket.Jdk {
    val channel = DatagramChannel.open()
    var opened = false
    try {
        val granted = buffers.applyTo(channel)
        val dontFragment = channel.forbidFragmentation()
        channel.bind(bind.toInetSocketAddress() ?: unresolved())
        when (peer) {
            is SocketPeer.Connected -> channel.connect(peer.peer.toInetSocketAddress() ?: unresolved())
            is SocketPeer.Unconnected -> Unit
        }
        channel.configureBlocking(false)
        val local = channel.localAddress
        if (local !is InetSocketAddress) unresolved()
        opened = true
        return StackSocket.Jdk(JdkSocket(channel, dontFragment), io, SocketBound(local.toPathKey(), granted))
    } finally {
        if (!opened) channel.close()
    }
}

internal actual fun jdkSocketIo(
    socket: StackSocket.Jdk,
    sends: List<NativeBytes>,
    maxPeersCached: Int,
): SocketIo {
    val nio = NioOneByOne(socket.socket.channel, sends.map { SendView(it, jdkView(it)) }, socket.loop.receiveViews.views, maxPeersCached)
    return SocketIo(nio, SocketRef(Supplier { nio.channel }))
}

internal actual fun descriptorRef(io: NativeIo): SocketRef = SocketRef(IntSupplier { io.descriptor() })

/** The JDK view a channel writes [bytes] through. */
private fun jdkView(bytes: NativeBytes): JdkBytes =
    when (val backed = JdkBytes.of(bytes)) {
        is JdkBacked.Backed -> backed.bytes
        is JdkBacked.NotJdkBacked -> raise(Raised.FactoryNotNative(bytes.owner), QuicCloseCircumstance.HandshakeFailed)
    }

private fun unresolved(): Nothing =
    raise(Raised.RouteUnresolved(RouteProbeFailure.SourceAddressUnknown), QuicCloseCircumstance.HandshakeFailed)

/** A JVM has no Network.framework: no value of this type exists. */
internal actual class NwSocket private constructor(
    private val impossible: Nothing,
) {
    fun never(): Nothing = impossible
}

internal actual fun closeNwSocket(socket: NwSocket): Unit = socket.never()

internal actual fun nwSocketIo(
    socket: StackSocket.Network,
    wake: () -> Unit,
): SocketIo = socket.socket.never()
