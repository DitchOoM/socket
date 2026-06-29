package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Pluggable QUIC backend (the Ktor `HttpClient(engine)` model, at the QUIC layer).
 *
 * An engine knows how to [connect] a client and [bind] a server; it owns nothing about the
 * scope/lifecycle. The backend is `QuicheEngine` (Cloudflare quiche) in `socket-quic-quiche`, used on
 * jvm/android/linux and — over an NWConnection-UDP datapath — macOS/iOS. The platform default is
 * resolved via `defaultQuicEngine` (in `socket-quic-default`); a consumer overrides it by passing an
 * explicit engine.
 *
 * **Lifecycle contract — the engine is a constructor, not a factory you babysit.** [connect] /
 * [bind] return a [QuicConnection] / [QuicServer] that has already completed its handshake / bind.
 * The *public* entry points are [withQuicConnection] / [withQuicServer], which own the lifecycle:
 * they call the engine, run your block, and [close][QuicConnection.close] in a `finally`. The
 * returned handle therefore never escapes as the primary API surface — there is no factory to leak
 * on a dropped error path. Calling [connect] / [bind] directly is the advanced escape hatch and
 * makes you responsible for [close][QuicConnection.close].
 */
interface QuicEngine {
    /** What this engine supports, independent of any particular connection. */
    val capabilities: EngineCapabilities

    /**
     * Open a client QUIC connection to [hostname]:[port], suspending through the TLS 1.3 handshake.
     * The returned [QuicConnection] is established; the caller (normally [withQuicConnection]) owns
     * its [close][QuicConnection.close]. [timeout] bounds establishment.
     */
    suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection

    /**
     * Bind a QUIC server on [port] (0 = OS-assigned), [host] (null = all interfaces). The returned
     * [QuicServer] is bound; the caller (normally [withQuicServer]) owns its [close][QuicServer.close].
     */
    suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer
}
