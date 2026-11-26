package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.SocketOwnership
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.SocketRefs
import com.ditchoom.socket.quic.core.ready.notPolledRef
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.Granted
import com.ditchoom.socket.quic.core.udp.GrantedBuffers
import com.ditchoom.socket.quic.core.udp.GroRequest
import com.ditchoom.socket.quic.core.udp.IoDrops
import com.ditchoom.socket.quic.core.udp.LentDrops
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NativeOpened
import com.ditchoom.socket.quic.core.udp.NativeSocket
import com.ditchoom.socket.quic.core.udp.PeerKeys
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.SocketReport
import com.ditchoom.socket.quic.isWildcard

/**
 * A socket opened for a loop, on that loop's stack: a JDK channel beside the loop's receive views, or a `qnet` socket
 * beside the loop's receive records, or a client's Network.framework connection, which reports its own readiness; or a
 * channel the caller lent, which rides any stack. Each variant carries what only its own stack has, so a socket of one
 * stack can never be put on a loop of another.
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

    /**
     * A channel the caller lent, on any loop's stack: no poller watches it and the core never closes it. [local] is the
     * address the caller bound; its io's slots come from [factory], and what it drops is counted in [drops].
     */
    class Lent(
        val channel: LentChannel,
        val peers: LentPeers,
        val local: PathKey,
        val factory: BufferFactory,
        val drops: LentDrops = LentDrops(),
    ) : StackSocket
}

/** This socket's granted buffers, bound to [port], with what the kernel and its own io drop. */
internal fun StackSocket.report(port: Int): SocketReport = SocketReport(granted(), port, io = ioDrops())

/** What this socket's own io drops: counted for a lent channel, the kernel's alone otherwise. */
internal fun StackSocket.ioDrops(): IoDrops =
    when (this) {
        is StackSocket.Jdk, is StackSocket.Native, is StackSocket.Network -> IoDrops.Kernel
        is StackSocket.Lent -> IoDrops.Lent(drops)
    }

/** Who owns this socket: the loop, or the caller who lent it. */
internal fun StackSocket.ownership(): SocketOwnership =
    when (this) {
        is StackSocket.Jdk, is StackSocket.Native, is StackSocket.Network -> SocketOwnership.Owned
        is StackSocket.Lent -> SocketOwnership.Lent
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
        is StackSocket.Lent -> local
    }

internal fun StackSocket.granted(): Granted =
    when (this) {
        is StackSocket.Jdk -> Granted.Reported(bound.granted)
        is StackSocket.Native -> Granted.Reported(bound.granted)
        is StackSocket.Network, is StackSocket.Lent -> Granted.NotReported
    }

/** Closes a socket whose io never reached a loop: a setup that failed before its connection started. A lent one stays open. */
internal fun StackSocket.close() {
    when (this) {
        is StackSocket.Jdk -> closeJdkSocket(socket)
        is StackSocket.Native -> socket.cell.close { fd -> loop.shim.close(fd) }
        is StackSocket.Network -> closeNwSocket(socket)
        is StackSocket.Lent -> Unit
    }
}

/** When the loop reads this socket: when its poller says so, or every pass for one that reports by completion. */
internal fun StackSocket.readiness(): Readiness =
    when (this) {
        is StackSocket.Jdk, is StackSocket.Native -> Readiness.Polled
        is StackSocket.Network, is StackSocket.Lent -> Readiness.EveryPass
    }

/** A socket's batch io and the handles its loop's poller watches. */
internal class SocketIo(
    val io: DatagramBatchIo,
    val refs: SocketRefs,
)

/**
 * The io of [this] socket over the send batches in [sends] (the loop's batch first, then each caller lane's, by lane). A
 * server's socket keeps up to [maxPeersCached] reply addresses on the JDK stack, and the keys of [peerSets] pairs of
 * recent senders on the native stack. A socket that reports by completion calls [wake] when the loop has news to read.
 */
internal fun StackSocket.ioOf(
    sends: List<NativeBytes>,
    maxPeersCached: Int,
    peerSets: Int,
    wake: () -> Unit,
): SocketIo =
    when (this) {
        is StackSocket.Jdk -> jdkSocketIo(this, sends, maxPeersCached)
        is StackSocket.Native -> {
            val native = NativeIo(loop.shim, socket, loop.sendRecords, loop.receiveRecords, PeerKeys(peerSets))
            SocketIo(native, SocketRefs(descriptorRef(native), secondDescriptorRef(native)))
        }
        is StackSocket.Network -> nwSocketIo(this, wake)
        is StackSocket.Lent -> SocketIo(LentIo(channel, peers, factory, drops, wake), SocketRefs(notPolledRef(), notPolledRef()))
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
                        GroRequest.of(ceiling),
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
                is NativeOpened.LayoutMismatch ->
                    raise(Raised.SpecLayoutRefused(opened.mismatch), QuicCloseCircumstance.HandshakeFailed)
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

/** Closes a JDK channel no io was made over. */
internal expect fun closeJdkSocket(socket: JdkSocket)

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

/** The handle the poller watches for a native socket's second descriptor, read when it is added. */
internal expect fun secondDescriptorRef(io: NativeIo): SocketRef

/** A client's Network.framework connection: made only on Apple's Kotlin/Native targets. */
internal expect class NwSocket

/** Cancels a Network.framework connection no io was made over. */
internal expect fun closeNwSocket(socket: NwSocket)

internal expect fun nwSocketIo(
    socket: StackSocket.Network,
    wake: () -> Unit,
): SocketIo
