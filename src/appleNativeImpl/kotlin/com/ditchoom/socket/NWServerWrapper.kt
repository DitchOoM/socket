package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.SocketErrorTypePosix
import com.ditchoom.socket.nwhelpers.nw_helper_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_create_listener
import com.ditchoom.socket.nwhelpers.nw_helper_ipv4_tcp_port_probe
import com.ditchoom.socket.nwhelpers.nw_helper_listener_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_listener_port
import com.ditchoom.socket.nwhelpers.nw_helper_listener_set_new_connection_handler
import com.ditchoom.socket.nwhelpers.nw_helper_listener_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_listener_start
import com.ditchoom.socket.nwhelpers.nw_helper_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.nw_listener_t
import platform.posix.EADDRINUSE
import platform.posix.strerror
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Server socket implementation using Apple's Network.framework.
 *
 * Accepts incoming connections and returns zero-copy socket wrappers.
 */
@OptIn(ExperimentalForeignApi::class)
class NWServerWrapper(
    private val config: TransportConfig = TransportConfig(),
) : ServerSocket {
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
        val channel = Channel<ClientSocket>(Channel.UNLIMITED)
        acceptChannel = channel
        val ephemeral = port <= 0
        try {
            repeat(MAX_EPHEMERAL_LISTEN_ATTEMPTS) {
                when (val start = listen(portOwnedForIpv4(if (ephemeral) 0 else port), backlog, channel)) {
                    is ListenStart.Ready -> {
                        this.listener = start.listener
                        return channel.receiveAsFlow()
                    }
                    // The probe proved the port free for IPv4 only; its IPv6 half can still be held, or
                    // be taken between the probe's close and the listen. Any port both families agree on
                    // is right for an ephemeral request, so draw again; a port the caller named is refused.
                    is ListenStart.Failed -> if (!ephemeral || !start.addressInUse) throw start.error
                }
            }
            throw SocketIOException("No port free in both address families after $MAX_EPHEMERAL_LISTEN_ATTEMPTS draws")
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    /**
     * The port to listen on, taken from the **IPv4** table first.
     *
     * An NW listener is dual-stack `::`, and on Darwin that does not own the IPv4 half of its port: a
     * plain `AF_INET` socket already holding `0.0.0.0:P` lets the dual-stack listen on `P` succeed, and
     * then every connection to `127.0.0.1:P` goes to that other socket. With an ephemeral port this is
     * invisible: NW draws it from the IPv6 table, so it can hand out a port whose IPv4 half belongs to
     * another process. The connect to `127.0.0.1` then completes against a stranger, and this listener
     * never hears of it — a TLS client waits for a ServerHello that never comes. (`:socket-udp` binds
     * the same way and for the same reason; see its `wildcardPortOwnedForIpv4`.)
     *
     * So an `AF_INET` probe takes the port first, which by definition hands out a port free for IPv4,
     * and closes it before the listener binds. A named port whose IPv4 half is held is refused here,
     * rather than becoming a listener that is open and deaf over IPv4.
     */
    private fun portOwnedForIpv4(requestedPort: Int): Int {
        val probed = nw_helper_ipv4_tcp_port_probe(requestedPort)
        if (probed > 0) return probed
        val errno = -probed
        throw SocketIOException(
            "Cannot listen on port $requestedPort: its IPv4 half is unavailable (${strerror(errno)?.toKString() ?: "errno $errno"})",
        )
    }

    /** How one listener start ended. A failed listener has already been cancelled. */
    private sealed interface ListenStart {
        class Ready(
            val listener: nw_listener_t,
        ) : ListenStart

        class Failed(
            val error: SocketException,
            /** NW reported `EADDRINUSE`: the port is held by another socket. */
            val addressInUse: Boolean,
        ) : ListenStart
    }

    /** Starts one listener on [port] and suspends until it is ready or cannot become ready. */
    private suspend fun listen(
        port: Int,
        backlog: Int,
        channel: Channel<ClientSocket>,
    ): ListenStart {
        val nwListener =
            nw_helper_create_listener(port, backlog)
                ?: throw SocketIOException("Failed to configure server listener")

        // Set new connection handler — each accepted connection gets a state handler
        // to wait until it's ready before sending to the channel.
        // Connection state: 0=invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
        nw_helper_listener_set_new_connection_handler(nwListener) { conn ->
            nw_helper_set_state_handler(conn) { state, _, _, _ ->
                when (state) {
                    3 -> { // ready
                        val clientSocket = NWSocketWrapper(config)
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

        // Listener state: 0=invalid, 1=waiting, 2=ready, 3=failed, 4=cancelled
        // NW calls this on a concurrent queue: the start is settled exactly once.
        val settled = AtomicInt(0)
        val start: ListenStart =
            try {
                suspendCancellableCoroutine<ListenStart> { continuation ->
                    nw_helper_listener_set_state_handler(nwListener) { state, errorDomain, errorCode, errorDesc ->
                        when (state) {
                            2 -> { // ready
                                listenerReady = true
                                if (settled.compareAndSet(0, 1)) continuation.resume(ListenStart.Ready(nwListener))
                            }
                            3 -> { // failed: a port held by another socket is POSIX EADDRINUSE
                                listenerReady = false
                                if (settled.compareAndSet(0, 1)) {
                                    val error = NWSocketWrapper.mapSocketException(errorDomain, errorDesc)
                                    val inUse = errorDomain == SocketErrorTypePosix && errorCode == EADDRINUSE
                                    continuation.resume(ListenStart.Failed(error, inUse))
                                } else {
                                    channel.close() // a listener that was ready has died
                                }
                            }
                            4 -> { // cancelled
                                if (listener === nwListener) {
                                    listenerReady = false
                                    channel.close()
                                    for (handler in closeHandlers) {
                                        handler()
                                    }
                                    closeHandlers.clear()
                                }
                            }
                        }
                    }

                    nw_helper_listener_start(nwListener)
                }
            } catch (e: Throwable) {
                nw_helper_listener_cancel(nwListener)
                throw e
            }
        if (start is ListenStart.Failed) nw_helper_listener_cancel(nwListener)
        return start
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

    private companion object {
        /** Fresh IPv4-probed ports an ephemeral listen draws before giving up; a collision is rare. */
        const val MAX_EPHEMERAL_LISTEN_ATTEMPTS = 8
    }
}
