package com.ditchoom.socket

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import com.ditchoom.socket.native.ServerListenerWrapper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Server socket implementation using Apple's Network.framework.
 *
 * Accepts incoming connections and returns zero-copy socket wrappers.
 */
@OptIn(ExperimentalForeignApi::class)
class NWServerWrapper : ServerSocket {
    private var listener: ServerListenerWrapper? = null
    private var acceptChannel: Channel<ClientSocket>? = null

    @OptIn(UnsafeNumber::class)
    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val serverListener = ServerListenerWrapper()

        if (!serverListener.configureWithPort(port.convert(), backlog.convert())) {
            throw SocketException("Failed to configure server listener")
        }

        // Create a channel to receive accepted connections
        val channel = Channel<ClientSocket>(Channel.UNLIMITED)
        acceptChannel = channel

        // Set accept handler BEFORE starting the listener
        serverListener.setAcceptHandlerWithHandler { acceptedSocket ->
            val clientSocket = NWSocketWrapper()
            clientSocket.socket = acceptedSocket
            channel.trySend(clientSocket)
        }

        serverListener.addCloseHandlerWithHandler {
            channel.close()
        }

        // Wait for the listener to start before returning
        this@NWServerWrapper.listener = suspendCancellableCoroutine { continuation ->
            serverListener.startWithCompletion { success, errorType, errorString ->
                if (success) {
                    continuation.resume(serverListener)
                } else {
                    channel.close()
                    continuation.resumeWithException(
                        NWSocketWrapper.mapSocketException(errorType, errorString),
                    )
                }
            }

            continuation.invokeOnCancellation {
                channel.close()
                serverListener.stopWithCompletion { }
            }
        }

        // Return the channel as a flow
        return channel.receiveAsFlow()
    }

    override fun isListening(): Boolean = listener?.isListening() ?: false

    @OptIn(UnsafeNumber::class)
    override fun port(): Int = listener?.boundPort()?.toInt() ?: -1

    override suspend fun close() {
        acceptChannel?.close()
        acceptChannel = null
        val serverListener = listener ?: return
        suspendCoroutine { continuation ->
            var completed = false

            fun complete() {
                if (!completed) {
                    completed = true
                    continuation.resume(Unit)
                }
            }

            serverListener.addCloseHandlerWithHandler {
                complete()
            }

            serverListener.stopWithCompletion {
                complete()
            }
        }
        listener = null
    }
}
