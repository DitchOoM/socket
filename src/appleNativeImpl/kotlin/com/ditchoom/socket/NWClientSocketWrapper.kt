package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_create_tcp_connection
import com.ditchoom.socket.nwhelpers.nw_helper_force_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNumber
import platform.Network.nw_connection_t
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
                        close = { nw_helper_force_cancel(it) },
                    ) { candidate -> connectTo(candidate.ip, port, host) }
                }
            }
        this.connection = ready
        this.closedLocally = false
        this.connectionReady = true
    }

    /** One connect to [endpoint], a name or a literal; [hostname] is what errors name. The ready connection is returned, not adopted. */
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

        suspendCancellableCoroutine { continuation ->
            var resumed = false

            nw_helper_set_state_handler(conn) { state, errorDomain, _, errorDesc ->
                if (resumed) return@nw_helper_set_state_handler

                when (state) {
                    3 -> { // ready
                        resumed = true
                        continuation.resume(Unit)
                    }
                    1, 4 -> { // waiting or failed
                        resumed = true
                        continuation.resumeWithException(
                            mapSocketException(errorDomain, errorDesc, hostname = hostname),
                        )
                    }
                    5 -> { // cancelled
                        resumed = true
                        continuation.resumeWithException(
                            SocketIOException(errorDesc ?: "Connection cancelled"),
                        )
                    }
                }
            }

            nw_helper_start(conn)

            continuation.invokeOnCancellation {
                if (!resumed) {
                    nw_helper_force_cancel(conn)
                }
            }
        }
        return conn
    }
}
