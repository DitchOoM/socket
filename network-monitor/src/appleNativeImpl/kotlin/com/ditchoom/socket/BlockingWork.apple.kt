package com.ditchoom.socket

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val blockingWork: CoroutineDispatcher = Dispatchers.IO
