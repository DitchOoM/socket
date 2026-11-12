package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * `withTimeout` for the bound on one stream read or write. A [kotlinx.coroutines.TimeoutCancellationException]
 * when [timeout] expires, and the expiry cancels [block], so a value produced at the same instant loses the
 * race exactly as with `withTimeout`.
 *
 * Both arm it on the library's shared deadline timer, not the caller's dispatcher's: kotlinx's own for
 * `Dispatchers.Default`, `Dispatchers.IO` and `runBlocking` is one `DefaultExecutor` thread that every
 * arming wakes (JVM and Android: `DeadlineTimer`; Kotlin/Native: `NativeDeadlineTimer`, whose worker also
 * took a lock on every arming). The shared timers wake only for a deadline earlier than the one they sleep towards.
 */
internal expect suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T
