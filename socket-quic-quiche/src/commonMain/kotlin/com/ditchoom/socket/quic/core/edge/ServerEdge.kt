@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.HandlerStart
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.core.demux.AcceptedPeer
import com.ditchoom.socket.quic.core.demux.ConnBuild
import com.ditchoom.socket.quic.core.demux.Encoding
import com.ditchoom.socket.quic.core.demux.Opened
import com.ditchoom.socket.quic.core.demux.ServerHooks
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.toSocketAddress
import com.ditchoom.socket.quic.traceRecorderFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The caller-facing side of the connections a server accepts: each gets its own trace capture, the same
 * [CoreConnection] a client uses, and is offered on [accepted] once its handshake completes or its client's 0-RTT is
 * accepted. Runs on the server's loop; nothing here blocks.
 */
internal class ServerEdge(
    private val options: QuicOptions,
    private val factory: BufferFactory,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
    private val accepted: SendChannel<CoreConnection>,
) {
    val hooks = ServerHooks(::encode, ::open)

    /** [key] as quiche reads it, in memory the accepted connection frees. */
    private fun encode(key: PathKey): Encoding {
        if (key.family != IPV4 && key.family != IPV6) return Encoding.Unencodable
        return Encoding.Encoded(key.encodeToNative(factory))
    }

    private fun open(
        peer: AcceptedPeer,
        build: ConnBuild,
    ): Opened {
        val capture = traceRecorderFor(options)
        val id = hex(peer.scid0) + hex(peer.scid1)
        build.nameQlog(capture, QuicSessionId(id))
        build.nameTrafficSecretsLog(capture, QuicSessionId(id))
        val edge = EdgeState(build.alpn, capture, TicketHome.NotKept)
        val conn = build.build(edge, capture)
        val scope = CoroutineScope(SupervisorJob())
        val connection =
            CoreConnection(
                conn,
                edge,
                factory,
                remoteOf(peer.peer),
                QuicConnectionIdentity(QuicSessionId(id), QuicWireConnectionId.Known(id)),
                readPolicy,
                writePolicy,
                scope,
            )
        // Offered once established, or once its 0-RTT is accepted; a connection that ends first is never offered. The offer
        // is made on the thread that decided it, the loop, with no hop: it only hands the connection to the acceptor.
        scope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            when (edge.start.first { it is HandlerStart.Decided }) {
                HandlerStart.Now -> accepted.trySend(connection)
                HandlerStart.Never, HandlerStart.NotYet -> Unit
            }
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
        const val IPV4 = 4
        const val IPV6 = 6
    }

    /** The peer as a caller reads it; a key that decoded to no family reads as the IPv4 wildcard. */
    private fun remoteOf(key: PathKey): SocketAddress =
        if (key.family == IPV4 || key.family == IPV6) key.toSocketAddress() else SocketAddress.ofLiteral("0.0.0.0", key.port)
}
