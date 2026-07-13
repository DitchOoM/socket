package com.ditchoom.socket.harness

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.IoTuning
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
 * The write-side analogue of [SilentPeer]: an in-process [ServerSocket] that accepts a single
 * client connection and then **never reads it and never closes it**. A peer that never `recv()`s
 * lets its socket receive buffer fill and advertise a zero window, so the *client's* writes
 * back-pressure — the deterministic "non-draining peer" the write-timeout contract needs
 * (`RFC_WRITE_TIMEOUT_CONTRACT.md`).
 *
 * This is harder to make deterministic than [SilentPeer]: a silent peer just needs to send nothing,
 * but a non-draining peer must reliably stall a *writer*, which depends on how much the two kernels
 * will buffer before blocking. Two levers make it fast and portable, needing no Docker/netem:
 *  - the peer binds with a small [IoTuning.receiveBuffer] (SO_RCVBUF) so its window closes quickly;
 *  - the client is expected to use a small [IoTuning.sendBuffer] (SO_SNDBUF) — see [nonDrainingClientConfig];
 *  - and the classifier ([writeOutcome]) writes far more than any autotuned buffer can hold, so even
 *    a platform that ignores the socket-buffer hints still stalls.
 *
 * Teardown ([close]) cancels the accept loop and closes both ends. Closing the client socket is what
 * unblocks any write still parked on the full send buffer, so an abandoned write unwinds here rather
 * than leaking past the test.
 */
class NonDrainingPeer private constructor(
    private val server: ServerSocket,
    private val scope: CoroutineScope,
    private val accepted: CompletableDeferred<ClientSocket>,
) {
    /** Ephemeral port the non-draining peer is listening on. */
    val port: Int get() = server.port()

    /**
     * The server-side end of the accepted connection, once a client has connected. Suspends up to
     * [timeout] for the accept to land. The test rarely needs this — the peer's whole job is to hold
     * the connection open while never reading it — but it synchronizes on the accept.
     */
    suspend fun awaitAccepted(timeout: Duration = 5.seconds): ClientSocket = withTimeout(timeout) { accepted.await() }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun close() {
        if (accepted.isCompleted) {
            runCatching { accepted.getCompleted().close() }
        }
        runCatching { server.close() }
        scope.cancel()
    }

    companion object {
        /** SO_RCVBUF / SO_SNDBUF hint (bytes) — small so the window closes and the writer stalls fast. */
        const val SMALL_SOCKET_BUFFER = 8 * 1024

        /**
         * Binds a non-draining peer on an ephemeral loopback port and starts its accept loop. Returns
         * as soon as the listener is bound; use [awaitAccepted] to synchronize on the connection.
         */
        suspend fun start(config: TransportConfig = peerConfig()): NonDrainingPeer {
            val server = ServerSocket.allocate(config)
            val flow = server.bind()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val accepted = CompletableDeferred<ClientSocket>()
            scope.launch {
                flow.collect { serverToClient ->
                    // Capture the first accepted socket and hold it: no read, ever. That silence on
                    // the read side is the whole fixture — it is what makes the client's writes stall.
                    if (!accepted.isCompleted) {
                        accepted.complete(serverToClient)
                    }
                }
            }
            return NonDrainingPeer(server, scope, accepted)
        }

        /** Server-side config: a small receive buffer so the peer's window closes quickly. */
        fun peerConfig(): TransportConfig = TransportConfig(io = IoTuning(receiveBuffer = SMALL_SOCKET_BUFFER))
    }
}

/**
 * The observable result of driving sustained writes at a [NonDrainingPeer], classified without ever
 * hanging the test — the write-side mirror of [ReadOutcome]. [writeOutcome] keeps writing chunks (each
 * bounded by the policy's deadline) on a detached scope and races the whole loop against a watchdog:
 *  - a platform that *enforces* a `Bounded` deadline yields [Threw] (with the concrete exception type,
 *    so the uniform-`SocketTimeoutException` axis can be asserted);
 *  - a platform that correctly **back-pressures by suspending** (the `UntilClosed`/infinite default, or
 *    a `Bounded` write still within its deadline) stalls inside one `write` and yields [WatchdogExpired];
 *  - a platform that neither suspends nor throws — i.e. it *reports success for merely queued bytes*
 *    and never back-pressures (Node's fire-and-forget) — races through the byte ceiling and yields
 *    [CompletedWithoutBackpressure].
 */
sealed interface WriteOutcome {
    /** A `write` terminated by throwing [error] after [elapsed] (an enforced `Bounded` deadline). */
    data class Threw(
        val error: Throwable,
        val elapsed: Duration,
    ) : WriteOutcome

    /**
     * A `write` neither returned nor threw within the watchdog — the writer is **suspended on
     * back-pressure**. This is the deterministic stand-in for "parked on a full send buffer" and the
     * expected outcome for the infinite-deadline default (RFC: writes back-pressure, they do not fail).
     */
    data object WatchdogExpired : WriteOutcome

    /**
     * The loop wrote [bytes] bytes to a peer that never read a single one, without ever suspending or
     * throwing — the write path is **not back-pressuring** (it acknowledged bytes it only queued). The
     * RED signal for Node's fire-and-forget default.
     */
    data class CompletedWithoutBackpressure(
        val bytes: Long,
        val elapsed: Duration,
    ) : WriteOutcome
}

/**
 * Drives writes at this socket under [deadline] and classifies the result as a [WriteOutcome] within
 * [watchdog], never propagating the write's exception into the caller's coroutine.
 *
 * It writes [chunkBytes]-sized chunks in a loop up to [maxBytes] total, re-presenting the same buffer
 * each iteration ([ReadBuffer.resetForRead]) so it generates continuous write pressure regardless of
 * whether a platform's `write` fully drains a buffer or returns partial. The point of the ceiling is
 * to bound a *non-back-pressuring* path (Node fire-and-forget) so it terminates as
 * [WriteOutcome.CompletedWithoutBackpressure] instead of exhausting memory.
 *
 * [watchdog] must be comfortably larger than [deadline] so an enforcing platform fires its own timeout
 * first; only a suspending (or non-enforcing) platform reaches the watchdog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun ClientSocket.writeOutcome(
    deadline: Duration,
    watchdog: Duration,
    chunkBytes: Int = 256 * 1024,
    maxBytes: Long = 64L * 1024 * 1024,
): WriteOutcome {
    val mark = TimeSource.Monotonic.markNow()
    val chunk: PlatformBuffer = filledBuffer(chunkBytes)
    // Detached scope: on a suspending platform the write stays parked past the watchdog; we abandon it
    // here and it unwinds when the socket closes in the test's finally / NonDrainingPeer.close().
    val writeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val write =
        writeScope.async {
            var total = 0L
            var zeroProgress = 0
            while (total < maxBytes) {
                // Rewind to re-present the full chunk. Use position(0) — NOT resetForRead(), which
                // re-flips (limit = position) and would yield an empty buffer once position is 0.
                chunk.position(0)
                val n = write(chunk, deadline).count
                total += n
                // Guard against a spin if a platform's write reports 0 bytes without blocking or
                // throwing — treat a run of no-progress writes as "not back-pressuring" and stop.
                if (n <= 0) {
                    if (++zeroProgress >= 64) break
                } else {
                    zeroProgress = 0
                }
            }
            total
        }
    val completedInTime = withTimeoutOrNull(watchdog) { write.join() } != null
    val outcome =
        if (!completedInTime) {
            WriteOutcome.WatchdogExpired
        } else {
            when (val error = write.getCompletionExceptionOrNull()) {
                null -> WriteOutcome.CompletedWithoutBackpressure(write.getCompleted(), mark.elapsedNow())
                else -> WriteOutcome.Threw(error, mark.elapsedNow())
            }
        }
    writeScope.cancel()
    return outcome
}

/** A [PlatformBuffer] of [size] bytes, ready to be written (position 0, limit [size]). */
private fun filledBuffer(size: Int): PlatformBuffer {
    val buffer = BufferFactory.Default.allocate(size)
    buffer.writeBytes(ByteArray(size))
    buffer.resetForRead()
    return buffer
}
