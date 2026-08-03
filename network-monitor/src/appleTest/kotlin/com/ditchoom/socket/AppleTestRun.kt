package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Runs [block] on **real** time with a real-time [timeout] — deliberately not `runTest`.
 *
 * `NWPathMonitor` delivers its updates from a dispatch queue on its own schedule. `runTest`'s virtual
 * clock would skip straight past any wait for that callback and report a timeout the instant nothing
 * was pending, so a live path-monitor test has to block on the real one.
 *
 * (`:socket` has a `runTestNoTimeSkipping` of the same shape, but it is wired into that module's
 * network test harness — `networkCapabilities()`, `TransportKind` — none of which exists or belongs
 * here. This is the two-line version those Apple tests actually used.)
 */
internal fun runOnRealTime(
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
) = runBlocking {
    withTimeout(timeout) { block() }
}
