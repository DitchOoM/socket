package com.ditchoom.socket.transport

import com.ditchoom.socket.TransportConfig

/**
 * A **multiplexed** transport: one establishment yields a held *session* that multiplexes many
 * streams (and, for QUIC/WebTransport, datagrams) over a single connection.
 *
 * This is the Layer-2 companion to [Transport] (RFC_UNIFIED_ESTABLISHMENT.md §3.2). Where [Transport]
 * is the transport-*agnostic* single-byte-stream surface a protocol library binds to, a
 * [SessionTransport] is the transport-*specific* surface a power user reaches for when they need the
 * connection's full multiplexing power. The session type [S] is deliberately transport-specific —
 * `QuicScope` has datagrams + migration, `WebTransportSession` has session close codes and uni/bidi
 * stream typing — because forcing them into one interface would either lie (stubbed capabilities) or
 * collapse to a uselessly-small common denominator. Capability differences stay expressed *by type*.
 *
 * ### The held ↔ scoped duality
 * A [SessionTransport] owns both ends of the session lifetime: [establish] hands back a held session,
 * and [close] tears it down. The scoped ergonomic (`withX { }`) is then not a separate hand-written
 * API per transport but the [use] extension over this pair — establish, run the block, close in a
 * `finally`. This is the single home for the held→scoped bridge that the existing
 * `withQuicConnection { }` (scoped) and `webTransportSupport().connect(url)` (held) shapes are the two
 * ends of; a future WebSocket session transport supplies [establish]/[close] and gets [use] for free.
 *
 * The [Transport] single-stream projection of a multiplexed transport is built *from* a
 * [SessionTransport]: establish the session, open one bidirectional stream, and return a
 * [com.ditchoom.buffer.flow.ByteStream] that closes the session when the stream closes.
 */
interface SessionTransport<S : Any> {
    /**
     * Establish and return a held session to [hostname]:[port]. Suspends through the transport
     * handshake; throws (a [com.ditchoom.socket.SocketException]) if establishment fails. The caller
     * owns the returned session and must eventually [close] it — or use the [use] extension, which
     * does so automatically.
     */
    suspend fun establish(
        hostname: String,
        port: Int,
        config: TransportConfig = TransportConfig(),
    ): S

    /** Tear down a session previously returned by [establish]. Idempotent per the session's own contract. */
    suspend fun close(session: S)
}

/**
 * Scoped ergonomic over a [SessionTransport]: [establish] a session, run [block] with it, and
 * [close][SessionTransport.close] it in a `finally` — even on exception or cancellation. This is the
 * `withX { }` form for any multiplexed transport, defined once here instead of per transport.
 *
 * ```kotlin
 * QuicSessionTransport(QuicOptions()).use("example.com", 443) { scope ->
 *     val a = scope.openStream()
 *     val b = scope.openStream()
 *     // … both streams closed and the connection torn down when this block returns
 * }
 * ```
 */
suspend fun <S : Any, R> SessionTransport<S>.use(
    hostname: String,
    port: Int,
    config: TransportConfig = TransportConfig(),
    block: suspend (S) -> R,
): R {
    val session = establish(hostname, port, config)
    return try {
        block(session)
    } finally {
        close(session)
    }
}
