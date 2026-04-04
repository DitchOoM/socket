package com.ditchoom.socket.transport

import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A [MessageConnection] that automatically reconnects on failure.
 *
 * The [connect] factory is called on each (re)connect attempt. It should create a fresh
 * [CodecConnection], perform any protocol handshake (e.g., MQTT CONNECT/CONNACK),
 * and return the ready-to-use connection. Protocol state that must survive reconnects
 * (persistence, subscriptions) should be captured in the [connect] closure.
 *
 * When a [networkMonitor] is provided, backoff is automatically reset whenever the
 * network becomes [NetworkAvailability.AVAILABLE], triggering an immediate reconnect
 * attempt instead of waiting for the current backoff delay.
 *
 * ```kotlin
 * val conn = ReconnectingConnection(
 *     connect = {
 *         val codec = CodecConnection.connect("broker.example.com", 1883,
 *             MyCodec, MyCodec::peekFrameSize)
 *         codec.send(ConnectPacket(clientId = "my-client"))
 *         codec.receive().first() // await handshake response
 *         codec
 *     },
 *     classifier = DefaultReconnectionClassifier(),
 *     networkMonitor = NetworkMonitor.polling(), // platform-specific factory
 * )
 *
 * conn.receive().collect { message -> handle(message) }
 * ```
 */
class ReconnectingConnection<T>(
    private val connect: suspend () -> CodecConnection<T>,
    private val classifier: ReconnectionClassifier = DefaultReconnectionClassifier(),
    private val networkMonitor: NetworkMonitor = NetworkMonitor.AlwaysAvailable,
) : MessageConnection<T> {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastMessageReceived = MutableStateFlow<TimeSource.Monotonic.ValueTimeMark?>(null)

    /** Timestamp of the most recent decoded message, or `null` if none received yet. */
    val lastMessageReceived: StateFlow<TimeSource.Monotonic.ValueTimeMark?> = _lastMessageReceived.asStateFlow()

    private val _lastDataReceived = MutableStateFlow<TimeSource.Monotonic.ValueTimeMark?>(null)

    /** Timestamp of the most recent raw data from the transport, forwarded from the current [CodecConnection]. */
    val lastDataReceived: StateFlow<TimeSource.Monotonic.ValueTimeMark?> = _lastDataReceived.asStateFlow()

    private var currentConnection: CodecConnection<T>? = null

    @kotlin.concurrent.Volatile
    private var backoffReset = false
    private var closed = false

    /**
     * Resets the backoff delay so the next reconnect attempt happens immediately.
     *
     * This is called automatically when [networkMonitor] reports [NetworkAvailability.AVAILABLE].
     * You can also call it manually from other platform-specific callbacks.
     */
    fun resetBackoff() {
        backoffReset = true
    }

    override fun receive(): Flow<T> {
        check(!closed) { "ReconnectingConnection is closed" }
        return flow {
            var retryDelay = Duration.ZERO

            // Auto-reset backoff when network becomes available
            val monitorJob = launchNetworkMonitorJob()

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
                            // Forward raw data timestamp from the underlying CodecConnection
                            conn.lastDataReceived.value?.let { _lastDataReceived.value = it }
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
                monitorJob?.cancel()
            }
        }
    }

    override suspend fun send(message: T) {
        check(!closed) { "ReconnectingConnection is closed" }
        val conn = currentConnection ?: throw IllegalStateException("Not connected")
        conn.send(message)
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
    private suspend fun launchNetworkMonitorJob(): Job? {
        if (networkMonitor === NetworkMonitor.AlwaysAvailable) return null
        val scope = CoroutineScope(currentCoroutineContext())
        return scope.launch {
            networkMonitor.availability
                .filter { it == NetworkAvailability.AVAILABLE }
                .collect { resetBackoff() }
        }
    }
}
