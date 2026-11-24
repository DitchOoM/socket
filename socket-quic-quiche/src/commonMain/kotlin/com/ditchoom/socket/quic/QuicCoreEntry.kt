package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.core.edge.connectOnTheCore
import com.ditchoom.socket.quic.core.edge.serveOnTheCore
import kotlin.time.Duration

// The core's entry for :socket-quic-default, whose connectQuic and serveQuic applications call. Each connection and
// listener takes its memory from BufferFactory.network(), whose buffers quiche reads and writes in place.

/** `connectQuic` on the core: [peer]'s candidates raced, the winner handed out once its handshake completed. */
suspend fun connectQuicOnCore(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnect<QuicConnection> = connectOnTheCore(peer, options, transport, timeout, BufferFactory.network())

/** `connectQuic`'s block form on the core. */
suspend fun <R> connectQuicOnCore(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> = connectOnTheCore(peer, options, transport, timeout, BufferFactory.network(), block)

/** `serveQuic` on the core. */
suspend fun serveQuicOnCore(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> = serveOnTheCore(port, host, tls, options, BufferFactory.network())

/** `serveQuic`'s block form on the core. */
suspend fun <R> serveQuicOnCore(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    block: suspend (QuicListener) -> R,
): QuicServe<R> = serveOnTheCore(port, host, tls, options, BufferFactory.network(), block)
