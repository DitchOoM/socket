package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * [Dispatchers.Default]. Its timers fire on kotlinx's native `DefaultExecutor`, a single worker started
 * the first time any timer in the process is armed and never stopped, so there is no later thread start
 * for exhaustion to fail.
 */
internal actual val productionDriverContext: CoroutineContext = Dispatchers.Default
