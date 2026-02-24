package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_create_tcp_connection
import com.ditchoom.socket.nwhelpers.nw_helper_force_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNumber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Client socket implementation using Apple's Network.framework.
 *
 * Supports both plain TCP and TLS connections with zero-copy data transfer.
 * TLS is derived from [SocketOptions.tls] in [open].
 */
@OptIn(ExperimentalForeignApi::class)
class NWClientSocketWrapper :
    NWSocketWrapper(),
    ClientToServerSocket {

    // C API nw_connection_state_t values:
    // 0=invalid/setup, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions,
    ) {
        val host = hostname ?: "localhost"
        val tlsConfig = socketOptions.tls
        val useTls = tlsConfig != null
        val verifyCertificates = tlsConfig?.let { it.verifyCertificates && !it.allowSelfSigned } ?: true

        val conn = nw_helper_create_tcp_connection(
            host = host,
            port = port.toUShort(),
            use_tls = NSNumber(bool = useTls),
            verify_certs = NSNumber(bool = verifyCertificates),
            timeout_seconds = timeout.inWholeSeconds.toInt(),
        ) ?: throw SocketException("Failed to create NW connection")

        this.connection = conn
        this.closedLocally = false

        // Wait for connection to be established
        suspendCancellableCoroutine { continuation ->
            var resumed = false

            nw_helper_set_state_handler(conn) { state, errorDomain, _, errorDesc ->
                if (resumed) return@nw_helper_set_state_handler

                when (state) {
                    3 -> { // ready
                        resumed = true
                        connectionReady = true
                        continuation.resume(Unit)
                    }
                    1, 4 -> { // waiting or failed
                        resumed = true
                        continuation.resumeWithException(
                            mapSocketException(errorDomain, errorDesc),
                        )
                    }
                    5 -> { // cancelled
                        resumed = true
                        connectionReady = false
                        continuation.resumeWithException(
                            SocketException(errorDesc ?: "Connection cancelled"),
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
    }
}
