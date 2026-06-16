package com.ditchoom.socket.webtransport

import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.Http3Connection
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.quic.CertificateHash
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions
import com.ditchoom.socket.http3.WebTransportSession as Http3WebTransportSession

/**
 * The jvm/android/native WebTransport provider: a [WebTransportSupport.Multiplexed] backed by
 * socket-http3 / socket-quic (real QUIC on the classpath).
 *
 * ### Lifecycle (Fork 2 = option 1 now, evolve to 2)
 * [connect] returns a **held** session: a private, session-owned `CoroutineScope` runs
 * `withHttp3Connection(authority, port, webTransport = …) { connectWebTransport(authority, path); awaitCancellation() }`,
 * and the wrapping [WebTransportSession.close] cancels that scope (structured-concurrency teardown —
 * the connection can never be left in a half-open state). This keeps socket-quic's scope-only
 * `QuicScope` invariant untouched: there is no new unscoped `open()` primitive (rejected option 3).
 *
 * [connectMultiplexed] (the Phase-4 DONE bar) holds one HTTP/3 connection open under a private scope and
 * opens many sessions over it via [MultiplexedHttp3WebTransport.openSession]. [connect] stays a distinct
 * held-single-session path (option 1); folding it into `connectMultiplexed` + `openSession` is the
 * option-2 evolution, deferred.
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

    /** Core of [connect]: both the neutral overload and the native [Http3WebTransportConfig] one land here. */
    internal suspend fun connectInternal(
        url: String,
        config: Http3WebTransportConfig,
    ): WebTransportSession {
        val target = parseWebTransportUrl(url)
        // The session OWNS this scope (Fork 2 option 1): the held HTTP/3 connection lives for exactly as
        // long as the scope, and close() cancels it. Detached SupervisorJob — the held connection must
        // outlive this connect() call and is torn down only by close(), not by the caller's scope ending.
        val scope = CoroutineScope(SupervisorJob())
        val ready = CompletableDeferred<Http3WebTransportSession>()
        scope.launch {
            try {
                withHttp3Connection(
                    hostname = target.host,
                    port = target.port,
                    quicOptions = config.resolvedQuicOptions(),
                    connectionOptions = config.connectionOptions,
                    // Non-null so the connection advertises Extended CONNECT + H3 Datagrams (required by
                    // connectWebTransport). Default maxSessions: we only initiate here, but advertising a
                    // session budget is harmless and keeps the door open for peer-initiated streams.
                    webTransport = Http3WebTransportOptions(),
                ) {
                    val session =
                        try {
                            connectWebTransport(target.authority, target.path)
                        } catch (t: Throwable) {
                            // Report to the awaiting connect() and unwind cleanly (return — do NOT rethrow,
                            // which would reach the global handler; the connection tears down as the block exits).
                            ready.completeExceptionally(t)
                            return@withHttp3Connection
                        }
                    ready.complete(session)
                    // Hold the connection open until close() cancels the scope; the resulting
                    // CancellationException unwinds the block, which tears down the H3/QUIC connection.
                    awaitCancellation()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // The QUIC/TLS handshake or H3 bootstrap failed *before* the block ran (so the inner
                // catch never saw it), or the held connection later dropped. If ready already completed
                // this is a no-op; otherwise it unblocks ready.await() instead of hanging forever.
                ready.completeExceptionally(t)
            }
        }
        val http3Session =
            try {
                ready.await()
            } catch (c: CancellationException) {
                scope.cancel()
                throw c
            } catch (t: Throwable) {
                scope.cancel()
                throw WebTransportException("WebTransport connect to $url failed: ${t.message}", t)
            }
        return HeldHttp3WebTransportSession(NativeWebTransportSession(http3Session), scope)
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
                throw WebTransportException("WebTransport connect to $url failed: ${t.message}", t)
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
 * Map the neutral [WebTransportOptions] onto the native [Http3WebTransportConfig]. Today only
 * [WebTransportOptions.serverCertificateHashes] needs threading: when set, the neutral hashes are folded
 * into the default WebTransport QUIC options and verified per the default
 * ([CertificateHashVerification.HashOnly] — the leaf hash is the sole trust check, matching the
 * browser, so a self-signed leaf works identically on both); the native [connect]/[connectMultiplexed]
 * config overloads can opt into [CertificateHashVerification.RequireBoth] to additionally require chain
 * validation. [WebTransportOptions.allowPooling] is not yet acted on natively (no transparent
 * connection reuse), so a hash-less options maps to the plain default config.
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
                throw WebTransportException("WebTransport openSession $path on $authority failed: ${t.message}", t)
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
 * A [connect]-returned held session (Fork 2 option 1): the [NativeWebTransportSession] adapter plus the
 * private [scope] whose cancellation tears down the owning `withHttp3Connection`. Everything but [close]
 * delegates straight to the adapter; [close] additionally cancels the scope so the held HTTP/3
 * connection is released (no half-open state can survive).
 */
private class HeldHttp3WebTransportSession(
    private val delegate: NativeWebTransportSession,
    private val scope: CoroutineScope,
) : WebTransportSession by delegate {
    override suspend fun close(
        code: Int,
        reason: String,
    ) {
        try {
            delegate.close(code, reason)
        } finally {
            // Cancels awaitCancellation() inside the launched block → withHttp3Connection returns →
            // the HTTP/3 connection (and its QUIC connection) are torn down. Idempotent.
            scope.cancel()
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
