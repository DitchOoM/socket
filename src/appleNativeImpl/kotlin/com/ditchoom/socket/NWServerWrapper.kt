package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.SocketErrorTypePosix
import com.ditchoom.socket.nwhelpers.nw_helper_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_create_listener
import com.ditchoom.socket.nwhelpers.nw_helper_listen_port_probe
import com.ditchoom.socket.nwhelpers.nw_helper_listener_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_listener_port
import com.ditchoom.socket.nwhelpers.nw_helper_listener_set_new_connection_handler
import com.ditchoom.socket.nwhelpers.nw_helper_listener_set_state_handler
import com.ditchoom.socket.nwhelpers.nw_helper_listener_start
import com.ditchoom.socket.nwhelpers.nw_helper_remote_port
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
import kotlin.coroutines.Continuation
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
) : ServerSocket,
    ServerSocketDiagnostics {
    private var listener: nw_listener_t = null

    /** Port probes, listen attempts, listener states and each arriving connection's states; see [NwStateTrace]. */
    private val trace = NwStateTrace("NW listener")

    override fun diagnostics(): String = trace.render()

    private var acceptChannel: Channel<NWSocketWrapper>? = null

    @Volatile
    private var listenerReady = false

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        // A started nw_connection_t must be cancelled (connection.h: "a connection that has been started
        // must be cancelled"), so a socket that never reaches a consumer is closed rather than dropped.
        val channel = Channel<NWSocketWrapper>(Channel.UNLIMITED, onUndeliveredElement = { it.closeInternal() })
        acceptChannel = channel
        val ephemeral = port <= 0
        try {
            repeat(MAX_EPHEMERAL_LISTEN_ATTEMPTS) {
                val probed = nw_helper_listen_port_probe(if (ephemeral) 0 else port)
                trace.mark(if (probed > 0) "port probe: $probed free in both families" else "port probe: errno ${-probed}")
                if (probed <= 0) {
                    // The drawn port's IPv4 half is held: draw another. A named port is refused.
                    if (!ephemeral || -probed != EADDRINUSE) throw portUnavailable(port, -probed)
                    return@repeat
                }
                when (val start = listen(probed, backlog, channel)) {
                    is ListenStart.Ready -> {
                        this.listener = start.listener
                        return channel.receiveAsFlow()
                    }
                    // The port can be taken between the probe's close and the listen. Any port both
                    // families agree on is right for an ephemeral request, so draw again.
                    is ListenStart.Failed -> if (!ephemeral || !start.addressInUse) throw start.error
                }
            }
            throw SocketIOException("No port free in both address families after $MAX_EPHEMERAL_LISTEN_ATTEMPTS draws")
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    /*
     * Why the port is probed before NW listens on it.
     *
     * An NW listener is dual-stack `::`, and on Darwin that does not own the IPv4 half of its port: a
     * plain `AF_INET` socket already holding `0.0.0.0:P` lets the dual-stack listen on `P` succeed, and
     * then every connection to `127.0.0.1:P` goes to that other socket. With an ephemeral port this is
     * invisible: NW draws it from the IPv6 table, so it can hand out a port whose IPv4 half belongs to
     * another process. The connect to `127.0.0.1` then completes against a stranger, and this listener
     * never hears of it — a TLS client waits for a ServerHello that never comes. (`:socket-udp` binds
     * the same way and for the same reason; see its `wildcardPortOwnedForIpv4`.)
     *
     * So `nw_helper_listen_port_probe` draws an ephemeral port from the IPv6 table and then proves its
     * IPv4 half free with a throwaway `AF_INET` bind, closing both before the listener binds. It does
     * not draw from the IPv4 table: that allocator hands out ports in sequence and does not see
     * IPv6-only holders (a `::1` connection, a V6ONLY socket), and under load eight draws in a row all
     * collided with them. A named port whose IPv4 half is held is refused, rather than becoming a
     * listener that is open and deaf over IPv4.
     */
    private fun portUnavailable(
        requestedPort: Int,
        errno: Int,
    ) = SocketIOException(
        "Cannot listen on port $requestedPort: its IPv4 half is unavailable (${strerror(errno)?.toKString() ?: "errno $errno"})",
    )

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
        channel: Channel<NWSocketWrapper>,
    ): ListenStart {
        val nwListener =
            nw_helper_create_listener(port, backlog)
                ?: throw SocketIOException("Failed to configure server listener")

        // Set new connection handler — each accepted connection gets a state handler
        // to wait until it's ready before sending to the channel.
        // Connection state: 0=invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
        trace.mark("listen on $port")
        nw_helper_listener_set_new_connection_handler(nwListener) { conn ->
            val arrival = "connection from remote port ${nw_helper_remote_port(conn)}"
            trace.mark("$arrival arrived")
            // NW calls this on a concurrent queue. Once the connection is handed out, the socket owns
            // it: cancelling it here when the peer resets made the socket's pending read report
            // ECANCELED, as if this side had closed, instead of the peer's ECONNRESET.
            val handedOut = AtomicInt(0)
            nw_helper_set_state_handler(conn) { state, errorDomain, errorCode, errorDesc, nwEntryNs ->
                trace.connectionState(arrival, state, errorDomain, errorCode, errorDesc, nwEntryNs.toLong())
                when (state) {
                    3 -> { // ready
                        if (handedOut.compareAndSet(0, 1)) {
                            val clientSocket = NWSocketWrapper(config)
                            clientSocket.connection = conn
                            clientSocket.connectionReady = true
                            channel.handOut(clientSocket)
                        }
                    }
                    1, 4 -> { // waiting or failed before it was ever handed out
                        if (handedOut.value == 0) nw_helper_cancel(conn)
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
                    nw_helper_listener_set_state_handler(nwListener) { state, errorDomain, errorCode, errorDesc, nwEntryNs ->
                        trace.listenerState(state, errorDomain, errorCode, errorDesc, nwEntryNs.toLong())
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
        val channel = acceptChannel
        acceptChannel = null
        channel?.let {
            it.close()
            // Connections that were ready but never reached a consumer die with the server: nothing
            // else will ever hold them, and an un-cancelled one stays open for the peer.
            while (true) it.tryReceive().getOrNull()?.closeInternal() ?: break
        }
        val nwListener = listener ?: return
        suspendCoroutine { continuation ->
            val cancelled = ListenerCancelSettle(continuation)

            nw_helper_listener_set_state_handler(nwListener) { state, errorDomain, errorCode, errorDesc, nwEntryNs ->
                trace.listenerState(state, errorDomain, errorCode, errorDesc, nwEntryNs.toLong())
                if (state == 4) { // cancelled
                    listenerReady = false
                    cancelled.on()
                }
            }

            nw_helper_listener_cancel(nwListener)

            // If already not ready (never started or already cancelled), complete immediately
            if (!listenerReady) {
                cancelled.on()
            }
        }
        listener = null
    }

    private companion object {
        /** Ports an ephemeral listen draws before giving up; a collision is rare. */
        const val MAX_EPHEMERAL_LISTEN_ATTEMPTS = 8
    }
}

/**
 * Resumes a `close()` waiting for its listener to report `cancelled`, exactly once.
 *
 * Two parties can reach the resume: the NW state handler, on a concurrent queue, and the closer
 * itself, which resumes at once when the listener is already not ready. When the `cancelled` callback
 * is delivered before `nw_helper_listener_cancel` returns, both run together; a plain "completed" flag
 * let both read `false` and both resume, and Kotlin/Native aborts the process on the second resume.
 * Same shape as the UDP module's `NwConnectSettle`: a CAS before the resume.
 */
internal class ListenerCancelSettle(
    private val continuation: Continuation<Unit>,
) {
    private val settled = AtomicInt(0)

    fun on() {
        if (settled.compareAndSet(0, 1)) continuation.resume(Unit)
    }
}

/**
 * Hands a ready connection's socket to the accept channel. When the channel is closed (the server
 * closed, or its listener died, between the connection starting and becoming ready) nobody will ever
 * take the socket, and a started nw_connection_t that is not cancelled stays open for its peer.
 */
internal fun Channel<NWSocketWrapper>.handOut(socket: NWSocketWrapper) {
    if (trySend(socket).isFailure) socket.closeInternal()
}
