package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

internal actual suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T = withDeadlineOn(DeadlineTimer.Shared, timeout, block)
