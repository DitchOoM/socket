package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_create_listener
import com.ditchoom.socket.nwhelpers.nw_helper_listener_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_listener_port
import com.ditchoom.socket.nwhelpers.nw_helper_listener_set_new_connection_handler
import com.ditchoom.socket.nwhelpers.nw_helper_listener_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_listener_start
import com.ditchoom.socket.nwhelpers.nw_helper_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.nw_listener_t
import kotlin.concurrent.Volatile
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
    private var listener: nw_listener_t = null
    private var acceptChannel: Channel<ClientSocket>? = null
    private val closeHandlers = mutableListOf<() -> Unit>()

    @Volatile
    private var listenerReady = false

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val nwListener =
            nw_helper_create_listener(port, backlog)
                ?: throw SocketException("Failed to configure server listener")

        val channel = Channel<ClientSocket>(Channel.UNLIMITED)
        acceptChannel = channel

        // Set new connection handler — each accepted connection gets a state handler
        // to wait until it's ready before sending to the channel.
        // Connection state: 0=invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
        nw_helper_listener_set_new_connection_handler(nwListener) { conn ->
            nw_helper_set_state_handler(conn) { state, _, _, _ ->
                when (state) {
                    3 -> { // ready
                        val clientSocket = NWSocketWrapper()
                        clientSocket.connection = conn
                        clientSocket.connectionReady = true
                        channel.trySend(clientSocket)
                    }
                    1, 4 -> { // waiting or failed
                        nw_helper_cancel(conn)
                    }
                }
            }
            nw_helper_start(conn)
        }

        // Wait for the listener to start
        // Listener state: 0=invalid, 1=waiting, 2=ready, 3=failed, 4=cancelled
        this@NWServerWrapper.listener =
            suspendCancellableCoroutine { continuation ->
                nw_helper_listener_set_state_handler(nwListener) { state, errorDomain, _, errorDesc ->
                    when (state) {
                        2 -> { // ready
                            listenerReady = true
                            continuation.resume(nwListener)
                        }
                        3 -> { // failed
                            listenerReady = false
                            channel.close()
                            continuation.resumeWithException(
                                NWSocketWrapper.mapSocketException(errorDomain, errorDesc),
                            )
                        }
                        4 -> { // cancelled
                            listenerReady = false
                            channel.close()
                            for (handler in closeHandlers) {
                                handler()
                            }
                            closeHandlers.clear()
                        }
                    }
                }

                nw_helper_listener_start(nwListener)

                continuation.invokeOnCancellation {
                    channel.close()
                    nw_helper_listener_cancel(nwListener)
                }
            }

        return channel.receiveAsFlow()
    }

    override fun isListening(): Boolean = listenerReady

    override fun port(): Int =
        listener?.let {
            nw_helper_listener_port(it).toInt()
        } ?: -1

    override suspend fun close() {
        acceptChannel?.close()
        acceptChannel = null
        val nwListener = listener ?: return
        suspendCoroutine { continuation ->
            var completed = false

            fun complete() {
                if (!completed) {
                    completed = true
                    continuation.resume(Unit)
                }
            }

            closeHandlers.add { complete() }

            nw_helper_listener_set_state_handler(nwListener) { state, _, _, _ ->
                if (state == 4) { // cancelled
                    listenerReady = false
                    for (handler in closeHandlers) {
                        handler()
                    }
                    closeHandlers.clear()
                }
            }

            nw_helper_listener_cancel(nwListener)

            // If already not ready (never started or already cancelled), complete immediately
            if (!listenerReady) {
                complete()
            }
        }
        listener = null
    }
}
