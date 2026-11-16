package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.Granted
import com.ditchoom.socket.quic.core.udp.GrantedBuffers
import com.ditchoom.socket.quic.core.udp.GroMemory
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NativeOpened
import com.ditchoom.socket.quic.core.udp.NativeSocket
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.isWildcard

/**
 * A socket opened for a loop, on that loop's stack: a JDK channel beside the loop's receive views, or a `qnet` socket
 * beside the loop's receive records, or a client's Network.framework connection, which reports its own readiness.
 * Each variant carries what only its own stack has, so a socket of one stack can never be put on a loop of another.
 */
internal sealed interface StackSocket {
    class Jdk(
        val socket: JdkSocket,
        val loop: LoopIo.Jdk,
        val bound: SocketBound,
    ) : StackSocket

    class Native(
        val socket: NativeSocket,
        val loop: LoopIo.Native,
        val bound: SocketBound,
    ) : StackSocket

    class Network(
        val socket: NwSocket,
        /** The local address the connection's path leaves from. */
        val local: PathKey,
    ) : StackSocket
}

/** Where a socket was bound, and the buffer sizes the operating system granted it. */
internal class SocketBound(
    val local: PathKey,
    val granted: GrantedBuffers,
)

internal fun StackSocket.local(): PathKey =
    when (this) {
        is StackSocket.Jdk -> bound.local
        is StackSocket.Native -> bound.local
        is StackSocket.Network -> local
    }

internal fun StackSocket.granted(): Granted =
    when (this) {
        is StackSocket.Jdk -> Granted.Reported(bound.granted)
        is StackSocket.Native -> Granted.Reported(bound.granted)
        is StackSocket.Network -> Granted.NotReported
    }

/** When the loop reads this socket: when its poller says so, or every pass for one that reports by completion. */
internal fun StackSocket.readiness(): Readiness =
    when (this) {
        is StackSocket.Jdk, is StackSocket.Native -> Readiness.Polled
        is StackSocket.Network -> Readiness.EveryPass
    }

/** A socket's batch io and the handle its loop's poller watches. */
internal class SocketIo(
    val io: DatagramBatchIo,
    val ref: SocketRef,
)

/**
 * The io of [this] socket over the send batches in [sends] (the loop's batch first, then each caller lane's, by lane),
 * as [memory] holds them. A server's socket keeps up to [maxPeersCached] reply addresses on the JDK stack. A socket
 * that reports by completion calls [wake] when the loop has news to read.
 */
internal fun StackSocket.ioOf(
    memory: EdgeMemory,
    sends: List<NativeBytes>,
    maxDatagrams: Int,
    maxPeersCached: Int,
    wake: () -> Unit,
): SocketIo =
    when (this) {
        is StackSocket.Jdk -> jdkSocketIo(this, sends, maxPeersCached)
        is StackSocket.Native -> {
            val records = List(sends.size) { memory.native(NativeIo.SEND_RECORD * maxDatagrams) }
            val native = NativeIo(loop.shim, socket, records, loop.receiveRecords)
            SocketIo(native, descriptorRef(native))
        }
        is StackSocket.Network -> nwSocketIo(this, wake)
    }

/**
 * Opens a non-blocking UDP socket for a loop whose io is [io], bound to [bind] and, when [peer] is a peer, connected to
 * it. A native socket that cannot be opened fails the connect or the serve with its refusal; it never falls back to
 * the JDK.
 */
internal fun openStackSocket(
    io: LoopIo,
    memory: EdgeMemory,
    bind: PathKey,
    peer: SocketPeer,
    buffers: SocketBuffers,
    ceiling: UdpBatching,
    maxDatagrams: Int,
): StackSocket =
    when (io) {
        is LoopIo.Jdk -> openJdkSocket(io, bind, peer, buffers)
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
                        GroMemory.Allocating { bytes -> memory.native(bytes) },
                    )
            ) {
                is NativeOpened.Opened -> {
                    val socket = opened.socket
                    StackSocket.Native(
                        socket,
                        io,
                        SocketBound(socket.local, GrantedBuffers(buffers, socket.grantedReceive, socket.grantedSend)),
                    )
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
    bind: PathKey,
    peer: SocketPeer,
): PathKey =
    when (peer) {
        is SocketPeer.Unconnected -> if (bind.isWildcard()) PathKey(6, bind.port, 0L, 0L) else bind
        is SocketPeer.Connected -> bind
    }

/** A JDK channel and the loop that reads it: made only on a JVM. */
internal expect class JdkSocket

/** Opens a JDK channel for a loop on the JDK stack. */
internal expect fun openJdkSocket(
    io: LoopIo.Jdk,
    bind: PathKey,
    peer: SocketPeer,
    buffers: SocketBuffers,
): StackSocket.Jdk

internal expect fun jdkSocketIo(
    socket: StackSocket.Jdk,
    sends: List<NativeBytes>,
    maxPeersCached: Int,
): SocketIo

/** The handle the poller watches for a native socket: its descriptor now, read again after a reopen. */
internal expect fun descriptorRef(io: NativeIo): SocketRef

/** A client's Network.framework connection: made only on Apple's Kotlin/Native targets. */
internal expect class NwSocket

internal expect fun nwSocketIo(
    socket: StackSocket.Network,
    wake: () -> Unit,
): SocketIo
