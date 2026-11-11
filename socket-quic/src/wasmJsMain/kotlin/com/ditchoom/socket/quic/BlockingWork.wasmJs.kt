package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val blockingWork: CoroutineDispatcher = Dispatchers.Default
