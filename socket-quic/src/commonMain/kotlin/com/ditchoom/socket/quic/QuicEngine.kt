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
     * Bind a QUIC server on [port] (0 = OS-assigned), [host] (null = all interfaces), owning the
     * socket. The returned [QuicServer] is bound; the caller (normally [withQuicServer]) owns its
     * [close][QuicServer.close].
     *
     * Every engine can do this, so it stays the one method an engine must implement.
     */
    suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer

    /**
     * Bind a QUIC server according to [binding] — the general form. [QuicPortBinding.Own] is the
     * method above; [QuicPortBinding.Shared] runs the listener on a UDP port someone else owns and
     * demultiplexes to it (RFC 9443 port sharing, so QUIC can coexist with the ICE/media stacks).
     *
     * Defaulted rather than abstract so an engine written before shared ports existed keeps
     * compiling and keeps working for its own port. Such an engine advertises
     * [EngineCapabilities.supportsSharedPort] = false, which is the answer to consult — the throw
     * below is the backstop, not the API.
     */
    suspend fun bind(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer =
        when (binding) {
            is QuicPortBinding.Own -> bind(binding.port, binding.host, tlsConfig, quicOptions, timeout)
            is QuicPortBinding.Shared ->
                throw UnsupportedOperationException(
                    "This QUIC engine cannot serve a shared UDP port (EngineCapabilities.supportsSharedPort is false)",
                )
        }
}
