package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.SSLSocketException
import com.ditchoom.socket.SocketUnknownHostException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.core.edge.CoreQuicConnection
import com.ditchoom.socket.quic.core.edge.connectOnTheCore
import com.ditchoom.socket.quic.core.edge.serveOnTheCore
import com.ditchoom.socket.quic.core.edge.setupLane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Duration

// The core's entry for :socket-quic-default, whose connectQuic and serveQuic applications call. Each connection and
// listener takes its memory from BufferFactory.network(), whose buffers quiche reads and writes in place. A connection
// outcome is answered as a value here; only a cancellation and a programming error raise.

/** `connectQuic` on the core: [peer]'s candidates raced, the winner handed out once its handshake completed. */
suspend fun connectQuicOnCore(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnect<QuicConnection> = connected(peer, options, transport, timeout)

/** `connectQuic`'s block form on the core; what [block] throws reaches the caller as it is. */
suspend fun <R> connectQuicOnCore(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> =
    when (val connect = connected(peer, options, transport, timeout)) {
        is QuicConnect.NotConnected -> connect
        is QuicConnect.Connected -> QuicConnect.Connected(connect.value.asBlock(block), connect.race)
    }

/** `serveQuic` on the core; on the dual-stack wildcard `::` the port is owned for IPv4 as well ([Ipv4Half]). */
suspend fun serveQuicOnCore(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> = if (host == DUAL_STACK_WILDCARD) servedOnBothHalves(port, tls, options) else served(port, host, tls, options)

/**
 * A serve on `[::]:port` whose IPv4 half no other socket holds: the port is drawn from the IPv4 table first, and drawn
 * again, a bounded number of times, when the IPv6 half of a drawn port turns out to be taken.
 */
private suspend fun servedOnBothHalves(
    port: Int,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> {
    var draws = if (port == 0) WILDCARD_DRAWS else 1
    while (true) {
        val bindPort =
            when (val half = withContext(setupLane) { ipv4HalfOf(port) }) {
                is Ipv4Half.Free -> half.port
                is Ipv4Half.NotProbed -> half.port
                is Ipv4Half.Held -> return QuicServe.Failed(QuicSocketFault.OpenRefused(NativeSocketRefusal(SocketRefusal.EAddrInUse)))
            }
        val served = served(bindPort, DUAL_STACK_WILDCARD, tls, options)
        draws--
        if (draws == 0 || !inUse(served)) return served
    }
}

private fun inUse(served: QuicServe<QuicListener>): Boolean {
    val failed = served as? QuicServe.Failed ?: return false
    val fault = failed.fault as? QuicSocketFault.OpenRefused ?: return false
    val refused = fault.thrown as? NativeSocketRefusal ?: return false
    return refused.refusal == SocketRefusal.EAddrInUse
}

private suspend fun served(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> =
    try {
        serveOnTheCore(port, host, tls, options, BufferFactory.network())
    } catch (refused: NativeSocketRefusal) {
        QuicServe.Failed(QuicSocketFault.OpenRefused(refused))
    } catch (refused: QuicRefusedException) {
        QuicServe.Refused(refused.refusal)
    }

private const val DUAL_STACK_WILDCARD = "::"

/** How many ephemeral ports a dual-stack wildcard serve draws before it answers the last bind's refusal. */
private const val WILDCARD_DRAWS = 8

/** `serveQuic`'s block form on the core: the listener closes when [block] ends, and what [block] throws reaches the caller. */
suspend fun <R> serveQuicOnCore(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    block: suspend (QuicListener) -> R,
): QuicServe<R> =
    when (val serving = serveQuicOnCore(port, host, tls, options)) {
        is QuicServe.NotServing -> serving
        is QuicServe.Serving -> {
            val listener = serving.value
            try {
                QuicServe.Serving(block(listener))
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { listener.close() }
            }
        }
    }

/**
 * A raced connect on the core, its every outcome a value: the end of the last candidate's attempt, the alert a refused
 * server certificate was closed with, or no usable path for a name with no address or no route.
 */
private suspend fun connected(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnect<CoreQuicConnection> =
    try {
        connectOnTheCore(peer, options, transport, timeout, BufferFactory.network())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ended: QuicCloseException) {
        QuicConnect.Failed(ended.end)
    } catch (refused: QuicRefusedException) {
        QuicConnect.Refused(refused.refusal)
    } catch (rejected: SSLSocketException) {
        QuicConnect.Failed(QuicConnectionEnd.ByLocal(closingErrorOf(rejected)))
    } catch (unknown: SocketUnknownHostException) {
        QuicConnect.Failed(QuicConnectionEnd.NoUsablePath)
    } catch (unrouted: UnresolvedRouteSourceException) {
        QuicConnect.Failed(QuicConnectionEnd.NoUsablePath)
    } catch (unpinned: PinnedRadioUnavailableException) {
        QuicConnect.Failed(QuicConnectionEnd.NoUsablePath)
    } catch (refused: NativeSocketRefusal) {
        QuicConnect.Failed(QuicConnectionEnd.SocketFailed(QuicSocketFault.OpenRefused(refused)))
    }
