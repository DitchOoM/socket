package com.ditchoom.socket.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A `Dispatchers.IO` view as wide as `Dispatchers.Default`: see the expect declaration for why not Default. */
internal actual val codecWriterDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(maxOf(2, Runtime.getRuntime().availableProcessors()), "codec-writer")
