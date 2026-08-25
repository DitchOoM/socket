package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.canRouteOffLink
import com.ditchoom.socket.default
import com.ditchoom.socket.pathChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
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
 * The [monitorFactory] produces a [NetworkMonitor] that drives two network-aware behaviors:
 *  - **Backoff reset on reachability:** backoff is reset whenever the network becomes worth attempting
 *    ([canRouteOffLink]), triggering an immediate reconnect instead of waiting out the current delay.
 *    Deliberately *not* gated on confirmed reachability: attempting during Android's ~1s validation
 *    window beats stalling every reconnect by a second on every reassociation (RFC §6).
 *  - **Backoff raced against path changes:** while waiting out a reconnect backoff, a
 *    [pathChanges] emission (Wi‑Fi returned, cellular took over) abandons the remaining delay and
 *    re-attempts immediately — the strongest signal a previously-failing reconnect may now succeed.
 *    Keyed on **identity**, so a reachability transition on the same network is not mistaken for a
 *    migration.
 *
 * It defaults to [NetworkMonitor.default], the platform's best reactive monitor. The factory
 * is invoked once per [receive] collection, and the produced monitor is owned by this
 * connection: it is [closed][NetworkMonitor.close] when that collection terminates. No monitor
 * socket is opened until [receive] is collected. Pass `{ NetworkMonitor.AlwaysAvailable }` to
 * opt out of monitoring. A monitor that cannot identify links reports a constant `Unidentified` and
 * never emits a path change, so the path-change behaviors are inert until a real reactive producer is
 * present (Apple/Android/browser-JS), keeping default behavior identical to a plain backoff.
 *
 * The optional [liveness] seam, when installed, is driven on each [pathChanges]
 * emission: it probes whether the live connection is still alive and, if it reports
 * [Liveness.Result.Dead], tears the connection down so reconnection starts promptly instead of
 * waiting for transport keepalive / the OS TCP timeout to notice a half-open connection. Inert by
 * default (no seam installed, or a monitor that never reports path changes).
 *
 * ```kotlin
 * val conn = ReconnectingConnection(
 *     connect = {
 *         // `scope` owns the connection's writer, so it must outlive the connection (#382).
 *         val codec = CodecConnection.connect("broker.example.com", 1883,
 *             MyCodec, scope = appScope)
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
    private val liveness: Liveness? = null,
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
    private var livenessLost = false

    @Volatile
    private var closed = false

    /**
     * Mutual exclusion over the reconnect loop, which exactly one collector may run.
     *
     * This replaces a `@Volatile var receiving` guarded by `check(!receiving); receiving = true` —
     * check-then-act, measured admitting two collectors in 2/300 attempts. `receiving` was never a
     * lifecycle state; it was a mutual-exclusion latch written by hand, and it was doing a second job
     * besides: [send] read it as "is the loop running". A [Mutex] says the first out loud and answers
     * the second through [Mutex.isLocked], so the two readings cannot drift apart.
     *
     * `tryLock` keeps the loud, deterministic rejection: silently letting two collectors each open
     * their own connection would be a downgrade, not a simplification.
     */
    private val collecting = Mutex()

    /**
     * Resets the backoff delay so the next reconnect attempt happens immediately.
     *
     * This is called automatically when the [monitorFactory] monitor reports a state that
     * [canRouteOffLink]. You can also call it manually from other platform-specific callbacks.
     */
    fun resetBackoff() {
        backoffReset = true
    }

    override fun receive(): Flow<T> {
        check(!closed) { "ReconnectingConnection is closed" }
        return flow {
            // tryLock rather than `check(!receiving); receiving = true`: that pair is check-then-act,
            // and `@Volatile` publishes the write without making the pair atomic. Measured admitting
            // two collectors in 2/300 attempts (236/300 contended) — see
            // ReconnectingConnectionCollectorRaceTests. Two collectors here is worse than the same
            // defect was in CodecConnection: each runs the whole loop below, so each calls connect()
            // and each writes currentConnection, and the loser's connection is left with no reference
            // to close it.
            check(collecting.tryLock()) { "receive() is already being collected" }
            try {
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
                                // Race the backoff against a network-path change: a change of network
                                // identity is the strongest signal a failing reconnect may now succeed, so
                                // abandon the remaining delay and re-attempt immediately. A monitor that
                                // cannot identify links reports a constant, so this waits out the full
                                // backoff, identical to a plain delay().
                                withTimeoutOrNull(retryDelay) { monitor.pathChanges().first() }
                            }
                            backoffReset = false
                            val conn = connect()
                            currentConnection = conn
                            livenessLost = false
                            _state.value = ConnectionState.Connected
                            val livenessJob = launchLivenessJob(monitor, conn)
                            try {
                                conn.receive().collect {
                                    _lastMessageReceived.value = TimeSource.Monotonic.markNow()
                                    emit(it)
                                }
                            } finally {
                                livenessJob?.cancel()
                            }
                            if (livenessLost) {
                                // A liveness probe fired by a network-path change judged the connection
                                // dead and tore it down. The path just changed, so reconnect now rather
                                // than waiting out a backoff.
                                currentConnection = null
                                _state.value =
                                    ConnectionState.Disconnected(
                                        SocketIOException("connection liveness lost after network change"),
                                    )
                                retryDelay = Duration.ZERO
                                continue
                            }
                            // Stream ended cleanly — no retry
                            _state.value = ConnectionState.Disconnected()
                            return@flow
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            currentConnection = null
                            _state.value = ConnectionState.Disconnected(e)
                            if (livenessLost) {
                                // Connection was torn down by a liveness probe after a network change;
                                // its receive() surfaced the teardown as an error. Reconnect now.
                                retryDelay = Duration.ZERO
                                continue
                            }
                            when (val decision = classifier.classify(e)) {
                                is ReconnectDecision.GiveUp -> throw e
                                is ReconnectDecision.RetryAfter -> retryDelay = decision.delay
                            }
                        }
                    }
                } finally {
                    monitorJob?.cancel()
                    monitor.close()
                }
            } finally {
                // Unlocked last, after the monitor is closed: the next collector must not be able to
                // start while this one's monitor is still open.
                collecting.unlock()
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
        check(collecting.isLocked) { "send() requires receive() to be collected (it drives reconnection)" }
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
     * Launches a coroutine that resets backoff whenever the network becomes worth attempting.
     *
     * Keyed on [canRouteOffLink] rather than on every state emission, and deduplicated, so the reset
     * fires on the *transition* into a routable network — not repeatedly as reachability firms up from
     * [InternetAccess.Observed.Pending] to [InternetAccess.Observed.Confirmed] on the same link.
     *
     * Returns null if using [NetworkMonitor.AlwaysAvailable] (no monitoring needed).
     */
    private suspend fun launchNetworkMonitorJob(monitor: NetworkMonitor): Job? {
        if (monitor === NetworkMonitor.AlwaysAvailable) return null
        val scope = CoroutineScope(currentCoroutineContext())
        return scope.launch {
            monitor.state
                .map { it.canRouteOffLink }
                .distinctUntilChanged()
                .filter { it }
                .collect { resetBackoff() }
        }
    }

    /**
     * Launches a coroutine that, on each [pathChanges] emission, asks [liveness]
     * whether [conn] is still alive and tears it down if the probe reports [Liveness.Result.Dead],
     * so reconnection starts promptly instead of waiting for transport keepalive / the OS TCP
     * timeout to notice a half-open connection.
     *
     * Path changes are identity-keyed, which is what keeps this from firing spuriously: a validation
     * window or a suspended link changes the [NetworkState] without changing the network, and probing
     * liveness there would tear down a connection that is merely waiting.
     *
     * Returns null when no [liveness] seam is installed or the monitor never reports path changes
     * ([NetworkMonitor.AlwaysAvailable]). The job is cancelled when the connection's [receive]
     * collection ends.
     */
    private suspend fun launchLivenessJob(
        monitor: NetworkMonitor,
        conn: Connection<T>,
    ): Job? {
        val probe = liveness ?: return null
        if (monitor === NetworkMonitor.AlwaysAvailable) return null
        val scope = CoroutineScope(currentCoroutineContext())
        return scope.launch {
            monitor.pathChanges().collect {
                if (probe.probe() == Liveness.Result.Dead) {
                    livenessLost = true
                    runCatching { conn.close() }
                }
            }
        }
    }
}
