package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * `withTimeout` for the bound on one stream read or write. A [kotlinx.coroutines.TimeoutCancellationException]
 * when [timeout] expires, and the expiry cancels [block], so a value produced at the same instant loses the
 * race exactly as with `withTimeout`.
 *
 * The JVM and Android arm it as `withTimeout` does, on the caller's dispatcher's timer. Kotlin/Native arms
 * it on the shared deadline timer (`NativeDeadlineTimer`): the caller's dispatcher there is
 * `Dispatchers.Default`, whose timer is one worker that every arming takes a lock on.
 */
internal expect suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T
