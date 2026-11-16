package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.GrantedBuffers
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NativeOpened
import com.ditchoom.socket.quic.core.udp.NativeSocket
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.toInetSocketAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.function.IntSupplier
import java.util.function.Supplier

/**
 * A socket opened for a loop, on that loop's stack: a JDK channel beside the loop's receive views, or a `qnet` socket
 * beside the loop's receive records. Each variant carries the loop io of its own stack, so a socket of one stack can
 * never be put on a loop of the other.
 */
internal sealed interface StackSocket {
    class Jdk(
        val channel: DatagramChannel,
        val loop: LoopIo.Jdk,
        val bound: SocketBound,
    ) : StackSocket

    class Native(
        val socket: NativeSocket,
        val loop: LoopIo.Native,
        val bound: SocketBound,
    ) : StackSocket
}

/** Where a socket was bound, and the buffer sizes the operating system granted it. */
internal class SocketBound(
    val local: InetSocketAddress,
    val granted: GrantedBuffers,
)

internal fun StackSocket.bound(): SocketBound =
    when (this) {
        is StackSocket.Jdk -> bound
        is StackSocket.Native -> bound
    }

/** A socket's batch io and the handle its loop's poller watches. */
internal class SocketIo(
    val io: DatagramBatchIo,
    val ref: SocketRef,
)

/**
 * The io of [socket] over the send batches in [sends] (the loop's batch first, then each caller lane's, by lane), as
 * [memory] holds them. A server's socket keeps up to [maxPeersCached] reply addresses on the JDK stack.
 */
internal fun StackSocket.ioOf(
    memory: EdgeMemory,
    sends: List<NativeBytes>,
    maxDatagrams: Int,
    maxPeersCached: Int,
): SocketIo =
    when (this) {
        is StackSocket.Jdk -> {
            val nio = NioOneByOne(channel, sends.map { memory.jdk(it) }, loop.receiveViews, maxPeersCached)
            SocketIo(nio, SocketRef(Supplier { nio.channel }))
        }
        is StackSocket.Native -> {
            val records = List(sends.size) { memory.native(NativeIo.SEND_RECORD * maxDatagrams) }
            val native = NativeIo(loop.shim, socket, records, loop.receiveRecords)
            SocketIo(native, SocketRef(IntSupplier { native.descriptor() }))
        }
    }

/**
 * Opens a non-blocking UDP socket for a loop whose io is [io], bound to [bind] and, when [peer] is a peer, connected to
 * it. A native socket that cannot be opened fails the connect or the serve with its refusal; it never falls back to
 * the JDK.
 */
internal fun openStackSocket(
    io: LoopIo,
    memory: EdgeMemory,
    bind: InetSocketAddress,
    peer: SocketPeer,
    buffers: SocketBuffers,
    ceiling: UdpBatching,
    maxDatagrams: Int,
): StackSocket =
    when (io) {
        is LoopIo.Jdk -> {
            val channel = DatagramChannel.open()
            var opened = false
            try {
                val granted = buffers.applyTo(channel)
                channel.bind(bind)
                when (peer) {
                    is SocketPeer.Connected -> channel.connect(peer.peer.toInetSocketAddress() ?: unresolved())
                    is SocketPeer.Unconnected -> Unit
                }
                channel.configureBlocking(false)
                val local = channel.localAddress
                if (local !is InetSocketAddress) unresolved()
                opened = true
                StackSocket.Jdk(channel, io, SocketBound(local, granted))
            } finally {
                if (!opened) channel.close()
            }
        }
        is LoopIo.Native ->
            when (
                val opened =
                    NativeSocket.open(
                        io.shim,
                        memory.native(NativeSocket.SPEC_BYTES),
                        bindKeyOf(bind, peer),
                        peer,
                        buffers.receiveBytes,
                        buffers.sendBytes,
                        ceiling,
                        maxDatagrams,
                    )
            ) {
                is NativeOpened.Opened -> {
                    val socket = opened.socket
                    val local = socket.local.toInetSocketAddress() ?: unresolved()
                    StackSocket.Native(socket, io, SocketBound(local, GrantedBuffers(buffers, socket.grantedReceive, socket.grantedSend)))
                }
                is NativeOpened.Refused ->
                    raise(Raised.SocketRefused(NativeSocketRefusal(opened.refusal)), QuicCloseCircumstance.HandshakeFailed)
            }
    }

/**
 * An unconnected socket bound to the IPv4 wildcard listens on both families, as the JDK's channel does: it is opened as
 * an IPv6 wildcard socket that also takes IPv4.
 */
private fun bindKeyOf(
    bind: InetSocketAddress,
    peer: SocketPeer,
): PathKey =
    when (peer) {
        is SocketPeer.Unconnected -> if (bind.address.isAnyLocalAddress) PathKey(6, bind.port, 0L, 0L) else bind.toPathKey()
        is SocketPeer.Connected -> bind.toPathKey()
    }

private fun unresolved(): Nothing =
    raise(Raised.RouteUnresolved(RouteProbeFailure.SourceAddressUnknown), QuicCloseCircumstance.HandshakeFailed)
