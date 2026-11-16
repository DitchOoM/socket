@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.core.demux.AcceptedPeer
import com.ditchoom.socket.quic.core.demux.ConnBuild
import com.ditchoom.socket.quic.core.demux.Opened
import com.ditchoom.socket.quic.core.demux.PeerSockAddr
import com.ditchoom.socket.quic.core.demux.ServerHooks
import com.ditchoom.socket.quic.toInetSocketAddress
import com.ditchoom.socket.quic.toNativeSockAddr
import com.ditchoom.socket.quic.traceRecorderFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

/**
 * The caller-facing side of the connections a server accepts: each gets its own trace capture, the same
 * [CoreConnection] a client uses, and is offered on [accepted] once its handshake completes. Runs on the server's
 * loop; nothing here blocks.
 */
internal class ServerEdge(
    private val options: QuicOptions,
    private val factory: BufferFactory,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
    private val accepted: SendChannel<CoreConnection>,
) {
    val hooks = ServerHooks(::peerAddress, ::open)

    /** The sender's address as quiche reads it, in memory the accepted connection frees. */
    private fun peerAddress(key: PathKey): PeerSockAddr {
        val inet = key.toInetSocketAddress() ?: return PeerSockAddr.Unencodable
        val native = inet.toNativeSockAddr(factory)
        return PeerSockAddr.Encoded(EncodedSockAddr(native.buffer, native.address, native.length))
    }

    private fun open(
        peer: AcceptedPeer,
        build: ConnBuild,
    ): Opened {
        val capture = traceRecorderFor(options)
        val edge = EdgeState(build.alpn, capture)
        val conn = build.build(edge, capture)
        val scope = CoroutineScope(SupervisorJob())
        val id = hex(peer.scid0) + hex(peer.scid1)
        val inet = peer.peer.toInetSocketAddress() ?: UNKNOWN_PEER
        val connection =
            CoreConnection(
                conn,
                edge,
                factory,
                SocketAddress.ofLiteral(inet.address.hostAddress.substringBefore('%'), inet.port),
                QuicConnectionIdentity(QuicSessionId(id), QuicWireConnectionId.Known(id)),
                readPolicy,
                writePolicy,
                scope,
            )
        // Offered once established; a connection that ends first is never offered.
        scope.launch {
            val settled = connection.state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }
            if (settled is QuicConnectionState.Established) accepted.trySend(connection)
        }
        return Opened(conn, Runnable { scope.cancel() })
    }

    private fun hex(word: Long): String {
        val out = StringBuilder(HEX_PER_WORD)
        for (shift in HEX_PER_WORD - 1 downTo 0) out.append(HEX[((word ushr (shift * BITS_PER_HEX)) and HEX_MASK).toInt()])
        return out.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
        const val HEX_PER_WORD = 16
        const val BITS_PER_HEX = 4
        const val HEX_MASK = 0xFL
        val UNKNOWN_PEER = InetSocketAddress(0)
    }
}
