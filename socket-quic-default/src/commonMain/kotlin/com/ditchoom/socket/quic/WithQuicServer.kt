package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bind a QUIC server on [port] and run [block] with the resulting
 * [QuicServer]. When [block] returns (normally, exceptionally, or via
 * cancellation), the server is closed — UDP socket closed, all in-flight
 * drivers destroyed, all handler coroutines cancelled.
 *
 * This is the server-facing entry point and it owns the lifecycle: it asks the
 * platform's [QuicEngine][defaultQuicEngine] to [bind][QuicEngine.bind],
 * runs [block], and closes the server in a `finally`. The engine is a constructor,
 * not a factory the caller babysits — the returned [QuicServer] never escapes this
 * scope. The scope-only block boundary remains the lifecycle.
 *
 * @param port UDP port to bind (0 for OS-assigned ephemeral port)
 * @param host interface to bind (null for all interfaces)
 * @param tlsConfig server TLS certificate and key
 * @param quicOptions QUIC transport configuration
 */
suspend fun <R> withQuicServer(
    port: Int = 0,
    host: String? = null,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration = 15.seconds,
    block: suspend QuicServer.() -> R,
): R = withQuicServer(QuicPortBinding.Own(port, host), tlsConfig, quicOptions, timeout, block)

/**
 * Bind a QUIC server according to [binding] and run [block] with the resulting [QuicServer],
 * closing it when [block] returns — the general form of the [port]/[host] entry point above.
 *
 * Pass a [QuicPortBinding.Shared] to run QUIC on a UDP port somebody else owns, which is how a QUIC
 * stack coexists with the WebRTC family on one port (RFC 9443). The demultiplexer owns the socket
 * and hands QUIC a branch; closing this server closes only that branch, leaving the port up:
 *
 * ```kotlin
 * val mux = UdpSocket.bind(localPort = 443, bufferFactory = recvPool).demultiplex(scope)
 * launch { mux.datagrams.collect { iceAgent.onDatagram(it.protocol, it.datagram) } }
 *
 * withQuicServer(QuicPortBinding.Shared(mux.quic), tlsConfig, QuicOptions(listOf("h3", "my-proto"))) {
 *     connectionsByAlpn(
 *         "h3" to { serveHttp3(webTransport = WebTransportOptions(), onWebTransport = { … }) { … } },
 *         "my-proto" to { /* raw QUIC */ },
 *     )
 * }
 * ```
 *
 * @param binding the UDP port this listener binds, or the shared channel it rides
 * @param tlsConfig server TLS certificate and key
 * @param quicOptions QUIC transport configuration (GREASE is forced off on a shared port — RFC 9443 §3)
 */
suspend fun <R> withQuicServer(
    binding: QuicPortBinding,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration = 15.seconds,
    block: suspend QuicServer.() -> R,
): R {
    val server = defaultQuicEngine.bind(binding, tlsConfig, quicOptions, timeout)
    return try {
        server.block()
    } finally {
        server.close()
    }
}
