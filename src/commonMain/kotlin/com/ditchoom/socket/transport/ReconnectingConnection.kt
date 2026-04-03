package com.ditchoom.socket.transport

import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A [MessageConnection] that automatically reconnects on failure.
 *
 * The [connect] factory is called on each (re)connect attempt. It should create a fresh
 * [CodecConnection], perform any protocol handshake (e.g., MQTT CONNECT/CONNACK),
 * and return the ready-to-use connection. Protocol state that must survive reconnects
 * (persistence, subscriptions) should be captured in the [connect] closure.
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
 * )
 *
 * // Platform connectivity hook
 * connectivityManager.onAvailable { conn.resetBackoff() }
 *
 * conn.receive().collect { message -> handle(message) }
 * ```
 */
class ReconnectingConnection<T>(
    private val connect: suspend () -> CodecConnection<T>,
    private val classifier: ReconnectionClassifier = DefaultReconnectionClassifier(),
) : MessageConnection<T> {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Initialized)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var currentConnection: CodecConnection<T>? = null
    private var backoffReset = false
    private var closed = false

    /**
     * Resets the backoff delay so the next reconnect attempt happens immediately.
     *
     * Call this from platform connectivity callbacks (e.g., Android ConnectivityManager,
     * Apple SCNetworkReachability) when the network becomes available.
     */
    fun resetBackoff() {
        backoffReset = true
    }

    override fun receive(): Flow<T> {
        check(!closed) { "ReconnectingConnection is closed" }
        return flow {
            var retryDelay = Duration.ZERO
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
                    conn.receive().collect { emit(it) }
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
}
