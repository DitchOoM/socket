package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.allocate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The deterministic **"silent peer"** fixture required by
 * `RFC_READ_TIMEOUT_CONTRACT.md` §6: an in-process [ServerSocket] that accepts
 * a single client connection and then **never writes and never closes it**.
 *
 * This is the TCP analogue of QUIC's [`ManualDriverClock`]/`ImpairingProxy`
 * determinism seams — it needs no Docker, no public host, and no netem: it is
 * the library's own [ServerSocket] on loopback, so the identical `commonTest`
 * assertions run on every platform that has `FULL_SOCKET_ACCESS`.
 *
 * The peer is *controllable* so the non-destructiveness assertions (RFC §6
 * rows 3 & 5) can prove that a read that timed out left a live connection:
 *  - [send] pushes bytes from the server side *after* a client read has timed
 *    out, so a conformant second client read observes them.
 *  - the accepted socket is held open (not closed) for the test's lifetime.
 *
 * Teardown ([close]) cancels the accept loop and closes both ends. Closing the
 * client socket is what unblocks any read still parked on a non-enforcing
 * platform (e.g. the JVM blocking path), so abandoned reads unwind here rather
 * than leaking past the test.
 */
class SilentPeer private constructor(
    private val server: ServerSocket,
    private val scope: CoroutineScope,
    private val accepted: CompletableDeferred<ClientSocket>,
) {
    /** Ephemeral port the silent peer is listening on. */
    val port: Int get() = server.port()

    /**
     * The server-side end of the accepted connection, once a client has
     * connected. Suspends up to [timeout] for the accept to land.
     */
    suspend fun awaitAccepted(timeout: Duration = 5.seconds): ClientSocket = withTimeout(timeout) { accepted.await() }

    /**
     * Breaks the silence: writes [text] from the server side so a subsequent
     * client `read()` can observe it. Used to prove a timed-out read left the
     * connection usable (RFC §3.1 — "no data for 15s? read again").
     */
    suspend fun send(
        text: String,
        deadline: Duration = 5.seconds,
    ) {
        awaitAccepted().writeString(text, Charset.UTF8, deadline)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun close() {
        if (accepted.isCompleted) {
            runCatching { accepted.getCompleted().close() }
        }
        runCatching { server.close() }
        scope.cancel()
    }

    companion object {
        /**
         * Binds a silent peer on an ephemeral loopback port and starts its
         * accept loop. Returns as soon as the listener is bound; use
         * [awaitAccepted] to synchronize on the client's connection.
         */
        suspend fun start(config: TransportConfig = TransportConfig()): SilentPeer {
            val server = ServerSocket.allocate(config)
            val flow = server.bind()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val accepted = CompletableDeferred<ClientSocket>()
            scope.launch {
                flow.collect { serverToClient ->
                    // Capture the first accepted socket and hold it: no read,
                    // no write, no close. That silence is the whole fixture.
                    if (!accepted.isCompleted) {
                        accepted.complete(serverToClient)
                    }
                }
            }
            return SilentPeer(server, scope, accepted)
        }
    }
}

/**
 * The observable result of a bounded [readBuffer], classified without ever
 * hanging the test. The classifier ([readOutcome]) runs the read on a detached
 * scope and races it against a watchdog, so:
 *  - a platform that *enforces* the deadline yields [Threw] (with the concrete
 *    exception type, so Axis 3 can be asserted);
 *  - a platform that *ignores* the deadline (JVM blocking) yields
 *    [WatchdogExpired] instead of wedging the suite for the framework timeout;
 *  - a read that unexpectedly returns data yields [Returned].
 */
sealed interface ReadOutcome {
    /** The read terminated by throwing [error] after [elapsed]. */
    data class Threw(
        val error: Throwable,
        val elapsed: Duration,
    ) : ReadOutcome

    /** The read returned data after [elapsed] (no timeout occurred). */
    data class Returned(
        val elapsed: Duration,
    ) : ReadOutcome

    /**
     * The read neither returned nor threw within the watchdog — i.e. the
     * deadline was **not enforced**. This is the deterministic stand-in for
     * "hangs forever" (RFC Axis 1, the JVM blocking path).
     */
    data object WatchdogExpired : ReadOutcome
}

/**
 * Runs `readBuffer(deadline)` on this socket and classifies the result as a
 * [ReadOutcome] within [watchdog], never propagating the read's exception into
 * the caller's coroutine (so a raw `TimeoutCancellationException` from the
 * Apple/Node path is captured as [ReadOutcome.Threw], not a test crash).
 *
 * [watchdog] must be comfortably larger than [deadline] so an enforcing
 * platform always fires its own timeout first; only a non-enforcing platform
 * reaches the watchdog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun ClientSocket.readOutcome(
    deadline: Duration,
    watchdog: Duration,
): ReadOutcome {
    val mark = TimeSource.Monotonic.markNow()
    // Detached scope: on a non-enforcing platform the read stays parked past
    // the watchdog; we abandon it here and it unwinds when the socket closes
    // in SilentPeer.close(). A structured child would force us to join it.
    val readScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val read = readScope.async { readBuffer(deadline) }
    val completedInTime = withTimeoutOrNull(watchdog) { read.join() } != null
    val outcome =
        if (!completedInTime) {
            ReadOutcome.WatchdogExpired
        } else {
            when (val error = read.getCompletionExceptionOrNull()) {
                null -> ReadOutcome.Returned(mark.elapsedNow())
                else -> ReadOutcome.Threw(error, mark.elapsedNow())
            }
        }
    // join() never rethrew the child's exception; getCompletionExceptionOrNull
    // read it off the completed Deferred. Best-effort teardown of the scope
    // (a stuck blocking read only truly dies at socket close).
    readScope.cancel()
    return outcome
}
