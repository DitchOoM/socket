package com.ditchoom.socket.webtransport

import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLSocketException
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.Http3Connection
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.quic.CertificateHash
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.ditchoom.socket.http3.WebTransportException as Http3WebTransportException
import com.ditchoom.socket.http3.WebTransportFailure as Http3WebTransportFailure
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions

/**
 * The jvm/android/native WebTransport provider: a [WebTransportSupport.Multiplexed] backed by
 * socket-http3 / socket-quic (real QUIC on the classpath).
 *
 * ### Lifecycle (Fork 2 = option 2)
 * [connectMultiplexed] holds one HTTP/3 connection open under a private, detached `SupervisorJob` scope
 * (so it outlives the connect() call and is torn down only by [MultiplexedWebTransport.close], never by
 * the caller's scope ending) and opens many sessions over it via [MultiplexedHttp3WebTransport.openSession].
 * [connect] is just that with exactly one session opened, wrapped so the session's
 * [WebTransportSession.close] also closes the held connection — one held-scope implementation, no
 * duplicated bootstrap. This keeps socket-quic's scope-only `QuicScope` invariant untouched: there is no
 * new unscoped `open()` primitive (rejected option 3).
 */
internal class Http3WebTransportSupport : WebTransportSupport.Multiplexed {
    override suspend fun connect(
        url: String,
        options: WebTransportOptions,
    ): WebTransportSession = connectInternal(url, options.toNativeConfig())

    override suspend fun connectMultiplexed(
        url: String,
        options: WebTransportOptions,
    ): MultiplexedWebTransport = connectMultiplexedInternal(url, options.toNativeConfig())

    /**
     * Core of [connect]: both the neutral overload and the native [Http3WebTransportConfig] one land here.
     *
     * Fork 2 = option 2: a single held session is just a [connectMultiplexedInternal] connection with
     * exactly one session opened on it, whose [WebTransportSession.close] tears the whole connection down.
     * One held-scope implementation ([connectMultiplexedInternal]); no duplicated bootstrap dance.
     */
    internal suspend fun connectInternal(
        url: String,
        config: Http3WebTransportConfig,
    ): WebTransportSession {
        val path = parseWebTransportUrl(url).path
        val held = connectMultiplexedInternal(url, config)
        return try {
            // Single session over the held connection; closing it closes the connection (option-1 semantics).
            SingleSessionOverHeldConnection(held.openSession(path), held)
        } catch (c: CancellationException) {
            held.close()
            throw c
        } catch (t: Throwable) {
            // openSession already wraps failures as WebTransportException; just release the held connection.
            held.close()
            throw t
        }
    }

    /** Core of [connectMultiplexed]: both the neutral overload and the native config one land here. */
    internal suspend fun connectMultiplexedInternal(
        url: String,
        config: Http3WebTransportConfig,
    ): MultiplexedWebTransport {
        val target = parseWebTransportUrl(url)
        // Same held-scope shape as connect() (Fork 2 option 1), but what's held — and published via the
        // CompletableDeferred — is the live Http3Connection itself, not a single session: openSession()
        // calls connectWebTransport() on it repeatedly, each on its own CONNECT stream. The detached
        // SupervisorJob keeps the connection alive past this call; close() cancels it.
        val scope = CoroutineScope(SupervisorJob())
        val ready = CompletableDeferred<Http3Connection>()
        scope.launch {
            try {
                withHttp3Connection(
                    hostname = target.host,
                    port = target.port,
                    quicOptions = config.resolvedQuicOptions(),
                    connectionOptions = config.connectionOptions,
                    webTransport = Http3WebTransportOptions(),
                ) {
                    // Publish the bootstrapped connection; openSession() dials each session on it.
                    ready.complete(this)
                    // Hold it open until close() cancels the scope, which unwinds withHttp3Connection
                    // and tears down every session opened on this connection.
                    awaitCancellation()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Handshake/bootstrap failed before the block ran, or the held connection dropped.
                // No-op if ready already completed; otherwise unblocks the awaiting connectMultiplexed().
                ready.completeExceptionally(t)
            }
        }
        val connection =
            try {
                ready.await()
            } catch (c: CancellationException) {
                scope.cancel()
                throw c
            } catch (t: Throwable) {
                scope.cancel()
                throw WebTransportException(t.toNeutralWebTransportFailure(), t)
            }
        return MultiplexedHttp3WebTransport(connection, target.authority, scope)
    }
}

/**
 * The QUIC options to dial with: the caller's [Http3WebTransportConfig.quicOptions] as given, or the
 * WebTransport default (`h3` ALPN + DATAGRAM support — datagrams must be enabled or the session's
 * sendDatagram/datagrams would be dead).
 */
private fun Http3WebTransportConfig.resolvedQuicOptions(): QuicOptions = quicOptions ?: defaultWebTransportQuicOptions()

/** The default QUIC options for a WebTransport dial: `h3` ALPN + DATAGRAM support. */
private fun defaultWebTransportQuicOptions(): QuicOptions = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), datagrams = DatagramOptions())

/**
 * Cert-related TLS alert codes (RFC 8446/5246 §6): bad_certificate(42), unsupported_certificate(43),
 * certificate_revoked(44), certificate_expired(45), certificate_unknown(46), unknown_ca(48),
 * access_denied(49). Used to classify a QUIC [QuicError.CryptoError] as a certificate rejection vs a
 * generic handshake failure by its typed alert code — never by matching a message string.
 */
private val CERT_TLS_ALERTS: Set<Int> = setOf(42, 43, 44, 45, 46, 48, 49)

/**
 * Map a native WebTransport establishment failure onto the neutral typed [WebTransportFailure] by TYPE:
 *  - socket-http3's [Http3WebTransportException] → the matching neutral variant (its `when (failure)` is
 *    exhaustive, so a new http3 variant won't compile here until it's mapped);
 *  - a TLS/cert [SSLSocketException] (incl. certificate-pinning) → [WebTransportFailure.TlsHandshake],
 *    with [WebTransportFailure.TlsHandshake.badCertificate] read from the typed
 *    [SSLSocketException.reason];
 *  - a [QuicCloseException] carrying a [QuicError.CryptoError] → [WebTransportFailure.TlsHandshake],
 *    cert-vs-handshake decided by the typed [QuicError.CryptoError.tlsAlert];
 *  - anything else → [WebTransportFailure.SessionError] (the honest catch-all, cause preserved).
 *
 * This is the single seam that replaces the old `WebTransportException("...: ${t.message}")` rewrap the
 * neutral mapper then string-matched.
 */
internal fun Throwable.toNeutralWebTransportFailure(): WebTransportFailure =
    when (this) {
        is Http3WebTransportException ->
            when (val f = failure) {
                Http3WebTransportFailure.NotEnabledLocally -> WebTransportFailure.NotEnabledLocally
                Http3WebTransportFailure.PeerDoesNotSupport -> WebTransportFailure.PeerDoesNotSupport
                is Http3WebTransportFailure.ConnectRejected -> WebTransportFailure.ConnectRejected(f.status)
                Http3WebTransportFailure.DatagramsNotEnabled -> WebTransportFailure.DatagramsNotEnabled
            }
        is SSLSocketException ->
            WebTransportFailure.TlsHandshake(
                badCertificate = reason == ConnectionFailureReason.TlsBadCertificate,
                detail = message ?: "TLS handshake failed",
            )
        is QuicCloseException ->
            when (val q = quicError) {
                is QuicError.CryptoError ->
                    WebTransportFailure.TlsHandshake(
                        badCertificate = q.tlsAlert in CERT_TLS_ALERTS,
                        detail = message ?: "QUIC TLS handshake failed",
                    )
                else -> WebTransportFailure.SessionError(message ?: "WebTransport connection closed")
            }
        else -> WebTransportFailure.SessionError(message ?: this::class.simpleName ?: "WebTransport connect failed")
    }

/**
 * Map the neutral [WebTransportOptions] onto the native [Http3WebTransportConfig]. Today only
 * [WebTransportOptions.serverCertificateHashes] needs threading: when set, the neutral hashes are folded
 * into the default WebTransport QUIC options and verified per the default
 * ([CertificateHashVerification.HashOnly] — the leaf hash is the sole trust check, matching the
 * browser, so a self-signed leaf works identically on both); the native [connect]/[connectMultiplexed]
 * config overloads can opt into [CertificateHashVerification.RequireBoth] to additionally require chain
 * validation. [WebTransportOptions.allowPooling] is **intentionally** not mapped here: native has no
 * transparent (platform-decides) connection reuse — each [connect] dials a dedicated connection, and the
 * app-controlled equivalent is to hold a [WebTransportSupport.Multiplexed] and open many sessions on it
 * (see the [WebTransportOptions.allowPooling] KDoc). So a hash-less options maps to the plain default config.
 */
private fun WebTransportOptions.toNativeConfig(): Http3WebTransportConfig =
    if (serverCertificateHashes.isEmpty()) {
        Http3WebTransportConfig()
    } else {
        Http3WebTransportConfig(
            quicOptions =
                defaultWebTransportQuicOptions().copy(
                    serverCertificateHashes = serverCertificateHashes.map { CertificateHash(it.value, it.algorithm) },
                ),
        )
    }

/**
 * A held HTTP/3 connection (Fork 2 option 1) backing many WebTransport sessions — the native
 * [MultiplexedWebTransport]. [openSession] dials each session via [Http3Connection.connectWebTransport]
 * on the held [connection] (the connection's [authority] is fixed at connect time; only the path
 * varies); the returned [NativeWebTransportSession] is plain, so *its* close() ends only that session's
 * CONNECT stream. [close] cancels the owning [scope], unwinding the `withHttp3Connection` block and
 * tearing down the connection together with every session on it.
 */
private class MultiplexedHttp3WebTransport(
    private val connection: Http3Connection,
    private val authority: String,
    private val scope: CoroutineScope,
) : MultiplexedWebTransport {
    override suspend fun openSession(
        path: String,
        options: WebTransportOptions,
    ): WebTransportSession {
        val session =
            try {
                connection.connectWebTransport(authority, path)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                throw WebTransportException(t.toNeutralWebTransportFailure(), t)
            }
        return NativeWebTransportSession(session)
    }

    override suspend fun close() {
        // Cancels awaitCancellation() in the held block → withHttp3Connection returns → the HTTP/3
        // (and underlying QUIC) connection and all its sessions are torn down. Idempotent.
        scope.cancel()
    }
}

/** jvm/android/native: real QUIC on the classpath, so WebTransport is multiplexed-capable. */
actual fun webTransportSupport(): WebTransportSupport = Http3WebTransportSupport()

/**
 * A [connect]-returned single held session (Fork 2 option 2): one session opened on a held
 * [MultiplexedWebTransport] connection. Everything but [close] delegates to the session adapter; [close]
 * ends the session *and* closes the owning connection, so a single-session dial releases its whole
 * connection (no half-open state can survive) — the same observable lifetime as the old option-1 path.
 */
private class SingleSessionOverHeldConnection(
    private val delegate: WebTransportSession,
    private val owner: MultiplexedWebTransport,
) : WebTransportSession by delegate {
    override suspend fun close(
        code: UInt,
        reason: String,
    ) {
        try {
            delegate.close(code, reason)
        } finally {
            // Tears down the held HTTP/3 (and underlying QUIC) connection. Idempotent.
            owner.close()
        }
    }
}

/** Parsed pieces of a WebTransport `https://host[:port]/path` URL needed to dial + Extended-CONNECT. */
private data class WebTransportTarget(
    val host: String,
    val port: Int,
    val authority: String,
    val path: String,
)

/**
 * Split a WebTransport URL into the QUIC dial target ([host]/[port]), the `:authority` value (the
 * host[:port] exactly as written, for the Extended CONNECT), and the `:path`. WebTransport requires an
 * `https://` URL (draft-ietf-webtrans-http3 §3); the port defaults to 443 and the path to `/`.
 */
private fun parseWebTransportUrl(url: String): WebTransportTarget {
    val scheme = "https://"
    require(url.startsWith(scheme)) { "WebTransport URL must be https://, got: $url" }
    val rest = url.substring(scheme.length)
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val path = if (slash >= 0) rest.substring(slash) else "/"
    require(authority.isNotEmpty()) { "WebTransport URL has no authority: $url" }
    // Split host:port, but a ':' inside an IPv6 literal (…]) is not a port separator.
    val colon = authority.lastIndexOf(':')
    return if (colon >= 0 && authority.indexOf(']') < colon) {
        val port =
            authority.substring(colon + 1).toIntOrNull()
                ?: throw IllegalArgumentException("WebTransport URL has an invalid port: $url")
        WebTransportTarget(authority.substring(0, colon), port, authority, path)
    } else {
        WebTransportTarget(authority, 443, authority, path)
    }
}
