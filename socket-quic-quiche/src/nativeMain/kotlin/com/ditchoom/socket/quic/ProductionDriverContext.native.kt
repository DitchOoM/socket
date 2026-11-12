package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Default` with its timers on [NativeDeadlineTimer.Shared]. Without it every `select`'s
 * `onTimeout` and `withTimeout` the driver arms goes to kotlinx's `DefaultExecutor`, one worker whose queue
 * lock every arming on every connection takes (see [NativeDeadlineTimer]).
 */
internal actual val productionDriverContext: CoroutineContext = NativeDeadlineTimer.Shared.over(Dispatchers.Default)
