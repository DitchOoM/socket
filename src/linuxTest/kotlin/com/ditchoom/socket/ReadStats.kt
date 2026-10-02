package com.ditchoom.socket

import com.ditchoom.socket.harness.HarnessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

actual typealias TestRunResult = Unit

internal actual fun runTestNoTimeSkipping(
    count: Int,
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    runBlocking {
        val socketsBefore = IoUringManager.activeSockets
        try {
            withTimeout(timeout) {
                withContext(Dispatchers.Default.limitedParallelism(count)) {
                    block()
                }
            }
        } catch (e: UnsupportedOperationException) {
            if (networkCapabilities().transports.contains(TransportKind.TCP)) throw e
        } catch (t: Throwable) {
            // Every socket on this target rides the one process-wide io_uring ring, so a test that
            // times out or fails here may be reporting the ring's state, not its own logic: #561's
            // `partialReadHandling` 10 s timeout preceded an `io_uring_setup` ENOMEM in the next test,
            // and nothing said whether the two were one starvation or a coincidence. The manager's
            // ledger (rings alive, poller state) and the host's (kernel, memlock, memory, fds) at the
            // moment of failure is what answers that, and it is only readable from inside the
            // process while the process is still alive. Folded into the message — the job log shows
            // the exception, not stdout. The original stays as the cause.
            throw AssertionError("${t::class.simpleName}: ${t.message}\n${IoUringManager.diagnosticSnapshot()}", t)
        }
        assertSocketCountReturnsTo(socketsBefore)
    }

/**
 * Every socket a test opens it closes, and every close uncounts exactly what an open counted: the ring is
 * released on the last close, so a count that drifts either way means a ring that is never released or one
 * released under live sockets. A close may finish on another dispatcher just after the body returns, so the
 * count is given [SOCKET_COUNT_SETTLE] to come back before the test fails naming the drift.
 */
internal suspend fun assertSocketCountReturnsTo(before: Int) {
    val settled = withTimeoutOrNull(SOCKET_COUNT_SETTLE) { while (IoUringManager.activeSockets != before) delay(10) }
    if (settled != null) return
    val after = IoUringManager.activeSockets
    val drift =
        if (after > before) {
            "left ${after - before} socket(s) counted open"
        } else {
            "uncounted ${before - after} socket(s) it never counted"
        }
    throw AssertionError("this test $drift (io_uring active sockets before=$before after=$after)")
}

private val SOCKET_COUNT_SETTLE = 2.seconds

actual fun supportsIPv6(): Boolean = true // Linux supports IPv6

private val startMark = TimeSource.Monotonic.markNow()

actual fun currentTimeMillis(): Long = startMark.elapsedNow().inWholeMilliseconds

actual fun isRunningInSimulator(): Boolean = false

internal actual fun isWindowsJvm(): Boolean = false

internal actual fun harnessHost(): String = HarnessConfig.host

// K/Native io_uring sockets are pull-based, so a NonDrainingPeer reliably back-pressures the writer.
actual fun nonDrainingPeerIsReliable(): Boolean = true
