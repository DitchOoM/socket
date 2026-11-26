@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.EnvironmentSetting
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
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.keyLogDirectory
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
    private val counts: ServerCounts,
    /** Where accepted connections run application code: the offer, and their handlers. */
    private val seats: HandlerSeats,
) {
    /** The key log directory the environment names, read once when the server is set up. */
    private val keyLogDirectory: EnvironmentSetting = keyLogDirectory()

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
        build.nameTrafficSecretsLog(capture, keyLogDirectory, QuicSessionId(id))
        val edge = EdgeState(build.alpn, capture, TicketHome.NotKept, ChainHome.NotKept)
        val conn = build.build(edge, capture, QuicSessionId(id))
        val scope = CoroutineScope(SupervisorJob())
        val seat = seats.seat(QuicSessionId(id))
        val connection =
            CoreConnection(
                conn,
                edge,
                factory,
                remoteOf(peer.peer),
                QuicConnectionIdentity(QuicSessionId(id), QuicWireConnectionId.Known(id)),
                readPolicy,
                writePolicy,
                options.streamOpenPolicy.toDeadline(),
                scope,
                seat,
                options.abortCodes,
            )
        // Offered once established, or once its 0-RTT is accepted; a connection that ends first is never offered. The
        // decision is seen on the loop, with no hop; only then does the connection get its lane, and the offer itself runs
        // on that lane, so an acceptor that resumes where it is resumed runs on a pool thread, never on the loop.
        scope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            when (edge.start.first { it is HandlerStart.Decided }) {
                HandlerStart.Now -> seat.offer(Runnable { accepted.trySend(connection) })
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
