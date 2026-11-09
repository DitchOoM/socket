package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * JVM/Android [QuicEngine] backed by Cloudflare quiche (JNI on JDK 8–20, FFM on JDK 21+, selected
 * by [loadQuicheApi]) over NIO [java.nio.channels.DatagramChannel]. The `withQuicConnection` /
 * `withQuicServer` wrappers (in `:socket-quic-default`) own the lifecycle; this engine just builds
 * + establishes.
 *
 * Public SPI: `:socket-quic-default` names this as the JVM/Android `defaultQuicEngine` actual.
 */
object QuicheEngine : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            supportsMigration = true,
            supportsDatagrams = true,
            supportsServer = true,
            // quiche reads whatever channel it is handed, so a demultiplexed port is no different
            // to it than one it bound itself.
            supportsSharedPort = true,
            pinnableRadios = platformPinnableRadios,
        )

    override val peerCertificates: PeerCertificateSupport = PeerCertificateSupport.Available(JcaPeerCertificateBackend)

    /**
     * Establishment runs on [Dispatchers.IO], never on the caller's thread, which may be a UI thread:
     * the first connection in a process extracts and loads the native library, and every connection
     * may start the platform network monitor, read the platform trust store and write a CA bundle file.
     */
    override suspend fun connect(
        binding: QuicClientBinding,
        endpoint: QuicEndpoint,
        serverName: String,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection =
        offTheCallersThread(release = { it.close() }) {
            establish(binding, endpoint, serverName, quicOptions, transport, timeout)
        }

    private suspend fun establish(
        binding: QuicClientBinding,
        endpoint: QuicEndpoint,
        serverName: String,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection {
        capabilities.requirePinnable(binding, quicOptions)
        // Opt-in capture (QuicOptions.trace): record QUIC traffic via the driver seam, then tap the
        // client's NetworkMonitor into the same recorder. Off (trace == null) → tuning is the default.
        val capture = traceRecorderFor(quicOptions)
        // ONE monitor per connection, resolved here and handed to all three consumers below. See
        // `resolveNetworkMonitor`: sharing the instance is what keeps the observation sequence a
        // migration reports and the one `networkAtClose` reports indexing the same stream.
        val monitor = resolveNetworkMonitor(quicOptions.networkMonitor)
        val observation = ConnectionNetworkObservation.of(monitor, RealDriverClock)
        val connection =
            buildJvmQuicConnection(
                endpoint,
                serverName,
                quicOptions,
                transport,
                timeout,
                loadQuicheApi(),
                QuicheDriverTuning(captureFactory = { capture }, networkObservation = observation),
                binding,
            )
        observation.collectInto(connection)
        wireClientConnectivityTap(quicOptions, capture, connection, monitor)
        // Auto-migration (QuicOptions.migration, Automatic by default): re-home on a link change, or
        // on the connection's own evidence that the path it is on has stopped answering — the
        // driver's `pathLiveness`, which is why this is wired here and not from inside the reactor.
        wireAutoMigration(
            quicOptions,
            connection,
            monitor,
            connection.quicheDriver.pathLiveness,
            capture,
            standbyPathsFor(quicOptions, monitor, connection),
        )
        return connection
    }

    /** On [Dispatchers.IO] for the reasons [connect] is, plus the certificate and key file reads. */
    override suspend fun bind(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer =
        offTheCallersThread(release = { it.close() }) {
            buildJvmQuicServer(
                binding,
                tlsConfig,
                quicOptions,
                QuicheDriverTuning(captureFactory = {
                    traceRecorderFor(quicOptions)
                }),
            )
        }

    /** On [Dispatchers.IO]: the certificate is written to PEM files for the bind to read. */
    override suspend fun bind(
        binding: QuicPortBinding,
        certificate: PeerCertificate,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = offTheCallersThread(release = { it.close() }) { super.bind(binding, certificate, quicOptions, timeout) }
}

/**
 * Runs [block] on [Dispatchers.IO] and returns what it built. A caller cancelled just as [block]
 * finishes never receives the result — `withContext` discards it — and that is the ordinary way a
 * losing candidate of a connect race ends, so the discarded connection or server is [release]d here
 * rather than leaked.
 */
private suspend inline fun <T> offTheCallersThread(
    crossinline release: suspend (T) -> Unit,
    crossinline block: suspend () -> T,
): T {
    var built: T? = null
    try {
        return withContext(Dispatchers.IO) { block().also { built = it } }
    } catch (e: CancellationException) {
        built?.let { withContext(NonCancellable) { release(it) } }
        throw e
    }
}
