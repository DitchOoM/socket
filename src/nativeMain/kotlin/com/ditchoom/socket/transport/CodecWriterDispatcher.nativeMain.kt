package com.ditchoom.socket.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val codecWriterDispatcher: CoroutineDispatcher = Dispatchers.Default
