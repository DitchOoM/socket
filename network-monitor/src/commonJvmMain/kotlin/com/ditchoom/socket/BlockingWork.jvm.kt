package com.ditchoom.socket

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val blockingWork: CoroutineDispatcher = Dispatchers.IO
