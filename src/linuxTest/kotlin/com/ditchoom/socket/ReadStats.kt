package com.ditchoom.socket

import com.ditchoom.socket.harness.HarnessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.TimeSource

actual typealias TestRunResult = Unit

internal actual fun runTestNoTimeSkipping(
    count: Int,
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    runBlocking {
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
    }

actual fun supportsIPv6(): Boolean = true // Linux supports IPv6

private val startMark = TimeSource.Monotonic.markNow()

actual fun currentTimeMillis(): Long = startMark.elapsedNow().inWholeMilliseconds

actual fun isRunningInSimulator(): Boolean = false

internal actual fun isWindowsJvm(): Boolean = false

internal actual fun harnessHost(): String = HarnessConfig.host

// K/Native io_uring sockets are pull-based, so a NonDrainingPeer reliably back-pressures the writer.
actual fun nonDrainingPeerIsReliable(): Boolean = true
