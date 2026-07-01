package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.default
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A [Connection] that automatically reconnects on failure.
 *
 * The [connect] factory is called on each (re)connect attempt. It should create a fresh
 * [Connection], perform any protocol handshake (e.g., MQTT CONNECT/CONNACK),
 * and return the ready-to-use connection. Protocol state that must survive reconnects
 * (persistence, subscriptions) should be captured in the [connect] closure.
 *
 * The [monitorFactory] produces a [NetworkMonitor] whose availability drives backoff resets:
 * backoff is automatically reset whenever the network becomes [NetworkAvailability.AVAILABLE],
 * triggering an immediate reconnect attempt instead of waiting for the current backoff delay.
 * It defaults to [NetworkMonitor.default], the platform's best reactive monitor. The factory
 * is invoked once per [receive] collection, and the produced monitor is owned by this
 * connection: it is [closed][NetworkMonitor.close] when that collection terminates. No monitor
 * socket is opened until [receive] is collected. Pass `{ NetworkMonitor.AlwaysAvailable }` to
 * opt out of monitoring.
 *
 * ```kotlin
 * val conn = ReconnectingConnection(
 *     connect = {
 *         val codec = CodecConnection.connect("broker.example.com", 1883,
 *             MyCodec)
 *         codec.send(ConnectPacket(clientId = "my-client"))
 *         codec.receive().first() // await handshake response
 *         codec
 *     },
 *     classifier = DefaultReconnectionClassifier(),
 *     // monitorFactory defaults to { NetworkMonitor.default() }
 * )
 *
 * conn.receive().collect { message -> handle(message) }
 * ```
 */
class ReconnectingConnection<T>(
    private val connect: suspend () -> Connection<T>,
    private val classifier: ReconnectionClassifier = DefaultReconnectionClassifier(),
    private val monitorFactory: () -> NetworkMonitor = { NetworkMonitor.default() },
) : Connection<T> {
    override val id: Long = 0L

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastMessageReceived = MutableStateFlow<TimeSource.Monotonic.ValueTimeMark?>(null)

    /** Timestamp of the most recent decoded message, or `null` if none received yet. */
    val lastMessageReceived: StateFlow<TimeSource.Monotonic.ValueTimeMark?> = _lastMessageReceived.asStateFlow()

    @Volatile
    private var currentConnection: Connection<T>? = null

    @Volatile
    private var backoffReset = false

    @Volatile
    private var closed = false

    @Volatile
    private var receiving = false

    /**
     * Resets the backoff delay so the next reconnect attempt happens immediately.
     *
     * This is called automatically when the [monitorFactory] monitor reports
     * [NetworkAvailability.AVAILABLE]. You can also call it manually from other
     * platform-specific callbacks.
     */
    fun resetBackoff() {
        backoffReset = true
    }

    override fun receive(): Flow<T> {
        check(!closed) { "ReconnectingConnection is closed" }
        return flow {
            check(!receiving) { "receive() is already being collected" }
            receiving = true
            var retryDelay = Duration.ZERO

            // Auto-reset backoff when network becomes available. The monitor is created
            // per-collection and owned here — closed in the finally below.
            val monitor = monitorFactory()
            val monitorJob = launchNetworkMonitorJob(monitor)

            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        _state.value = ConnectionState.Connecting
                        if (retryDelay > Duration.ZERO && !backoffReset) {
                            delay(retryDelay)
                        }
                        backoffReset = false
                        val conn = connect()
                        currentConnection = conn
                        _state.value = ConnectionState.Connected
                        conn.receive().collect {
                            _lastMessageReceived.value = TimeSource.Monotonic.markNow()
                            emit(it)
                        }
                        // Stream ended cleanly — no retry
                        _state.value = ConnectionState.Disconnected()
                        return@flow
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.value = ConnectionState.Disconnected(e)
                        currentConnection = null
                        when (val decision = classifier.classify(e)) {
                            is ReconnectDecision.GiveUp -> throw e
                            is ReconnectDecision.RetryAfter -> retryDelay = decision.delay
                        }
                    }
                }
            } finally {
                receiving = false
                monitorJob?.cancel()
                monitor.close()
            }
        }
    }

    /**
     * Send a message, suspending during reconnection until connected.
     *
     * If the connection is currently reconnecting, this suspends until
     * [ConnectionState.Connected] is reached, then sends. Message ordering is preserved —
     * blocked writes resume after the connect lambda finishes (handshake + session prep done).
     *
     * Throws [IllegalStateException] if the connection is closed.
     */
    override suspend fun send(message: T) {
        check(!closed) { "ReconnectingConnection is closed" }
        check(receiving) { "send() requires receive() to be collected (it drives reconnection)" }
        while (currentCoroutineContext().isActive && !closed) {
            val conn = currentConnection
            if (conn == null) {
                // Wait for reconnection to complete
                state.first { it == ConnectionState.Connected || closed }
                if (closed) throw IllegalStateException("ReconnectingConnection is closed")
                continue
            }
            try {
                conn.send(message)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Connection dropped during send — wait for reconnect
            }
        }
        throw IllegalStateException("ReconnectingConnection is closed")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        currentConnection?.close()
        currentConnection = null
        _state.value = ConnectionState.Disconnected()
    }

    /**
     * Launches a coroutine that resets backoff whenever the network becomes available.
     * Returns null if using [NetworkMonitor.AlwaysAvailable] (no monitoring needed).
     */
    private suspend fun launchNetworkMonitorJob(monitor: NetworkMonitor): Job? {
        if (monitor === NetworkMonitor.AlwaysAvailable) return null
        val scope = CoroutineScope(currentCoroutineContext())
        return scope.launch {
            monitor.availability
                .filter { it == NetworkAvailability.AVAILABLE }
                .collect { resetBackoff() }
        }
    }
}
