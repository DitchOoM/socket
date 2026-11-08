package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_create_tcp_connection
import com.ditchoom.socket.nwhelpers.nw_helper_force_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSNumber
import platform.Network.nw_connection_t
import kotlin.concurrent.AtomicInt

/**
 * Client socket implementation using Apple's Network.framework.
 *
 * Supports both plain TCP and TLS connections with zero-copy data transfer.
 * TLS is derived from [TransportConfig.tls] in [open].
 */
@OptIn(ExperimentalForeignApi::class)
class NWClientSocketWrapper(
    config: TransportConfig = TransportConfig(),
) : NWSocketWrapper(config),
    ClientToServerSocket {
    // C API nw_connection_state_t values:
    // 0=invalid/setup, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
    override suspend fun open(
        port: Int,
        hostname: String?,
    ) {
        val host = hostname ?: "localhost"
        val ready =
            when (val resolution = config.nameResolution) {
                // Network.framework resolves the name and races the families itself (RFC 8305).
                NameResolution.Platform -> connectTo(host, port, host)
                is NameResolution.Via -> {
                    if (config.tls != null) {
                        throw UnsupportedOperationException(
                            "NameResolution.Via with TLS is not supported on Apple: Network.framework verifies the " +
                                "certificate against the endpoint it is given, and a resolved literal is not the name",
                        )
                    }
                    connectRace(
                        candidates = resolution.candidatesFor(host),
                        pacing = config.connectPacing,
                        verdict = AttemptVerdict::of,
                        close = { nw_helper_force_cancel(it) },
                    ) { candidate -> connectTo(candidate.ip, port, host) }
                }
            }
        this.connection = ready
        this.closedLocally = false
        this.connectionReady = true
    }

    /**
     * One connect to [endpoint], a name or a literal; [hostname] is what errors name. The ready
     * connection is returned, not adopted; one that failed or was cancelled is cancelled here.
     */
    private suspend fun connectTo(
        endpoint: String,
        port: Int,
        hostname: String,
    ): nw_connection_t {
        val tlsConfig = config.tls
        val useTls = tlsConfig != null
        val verifyCertificates = tlsConfig?.let { it.verifyCertificates && !it.allowSelfSigned } ?: true
        val conn =
            nw_helper_create_tcp_connection(
                host = endpoint,
                port = port.toUShort(),
                use_tls = NSNumber(bool = useTls),
                verify_certs = NSNumber(bool = verifyCertificates),
                timeout_seconds = config.connectTimeout.inWholeSeconds.toInt(),
                // Honor TransportConfig.io.tcpNoDelay like the JVM/Node/Linux paths do.
                // null (unset) keeps the platform default (Nagle on).
                no_delay = NSNumber(bool = config.io.tcpNoDelay == true),
            ) ?: throw SocketIOException("Failed to create NW connection")

        try {
            // Network.framework's own connection timeout (set in create_tcp_parameters) covers the TCP
            // handshake only. A TLS handshake has no bound there: a peer that accepts TCP and then says
            // nothing holds the connection in `preparing` for as long as the peer stays silent. So the
            // whole establishment, TCP and TLS together, is bounded here — the same bound the JVM and
            // Linux connects put on their TLS handshake.
            withTimeoutOrNull(config.connectTimeout) { awaitReady(conn, hostname) }
                ?: throw SocketTimeoutException(TimeoutContext.Connect(config.connectTimeout, hostname, port), hostname, port)
        } catch (e: Throwable) {
            nw_helper_force_cancel(conn)
            throw e
        }
        return conn
    }

    /** Suspends until [conn] is ready; a waiting, failed or cancelled connection resumes with its error. */
    private suspend fun awaitReady(
        conn: nw_connection_t,
        hostname: String,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        // NW calls the handler on a concurrent queue, so two state changes (a TLS `waiting`, then the
        // `cancelled` our own cleanup causes) can run at once: claim the single resume atomically.
        val resumed = AtomicInt(0)

        nw_helper_set_state_handler(conn) { state, errorDomain, _, errorDesc ->
            val outcome: Result<Unit>? =
                when (state) {
                    3 -> Result.success(Unit) // ready
                    1, 4 -> Result.failure(mapSocketException(errorDomain, errorDesc, hostname = hostname)) // waiting or failed
                    5 -> Result.failure(SocketIOException(errorDesc ?: "Connection cancelled")) // cancelled
                    else -> null
                }
            if (outcome != null && resumed.compareAndSet(0, 1)) continuation.resumeWith(outcome)
        }

        nw_helper_start(conn)
    }
}
