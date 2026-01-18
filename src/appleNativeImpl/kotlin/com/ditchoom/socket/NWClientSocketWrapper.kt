package com.ditchoom.socket

import com.ditchoom.socket.native.ClientSocketWrapper
import com.ditchoom.socket.native.SocketErrorTypeNone
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Client socket implementation using Apple's Network.framework.
 *
 * Supports both plain TCP and TLS connections with zero-copy data transfer.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class NWClientSocketWrapper(
    private val useTls: Boolean,
) : NWSocketWrapper(),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
    ) {
        val host = hostname ?: "localhost"
        val clientSocket =
            ClientSocketWrapper(
                host = host,
                port = port.toUShort(),
                timeoutSeconds = timeout.inWholeSeconds.convert(),
                useTLS = useTls,
            )
        this.socket = clientSocket

        // Wait for connection to be established
        suspendCancellableCoroutine { continuation ->
            var resumed = false

            clientSocket.setStateHandlerWithHandler { _, state, errorType, errorString ->
                if (resumed) return@setStateHandlerWithHandler

                when {
                    state?.startsWith("ready") == true -> {
                        resumed = true
                        continuation.resume(Unit)
                    }
                    errorType != SocketErrorTypeNone -> {
                        resumed = true
                        continuation.resumeWithException(
                            mapSocketException(errorType, errorString),
                        )
                    }
                    state?.startsWith("failed") == true || state?.startsWith("cancelled") == true -> {
                        resumed = true
                        continuation.resumeWithException(
                            SocketException(errorString ?: "Connection failed: $state"),
                        )
                    }
                }
            }

            clientSocket.start()

            continuation.invokeOnCancellation {
                if (!resumed) {
                    clientSocket.forceClose()
                }
            }
        }
    }
}
