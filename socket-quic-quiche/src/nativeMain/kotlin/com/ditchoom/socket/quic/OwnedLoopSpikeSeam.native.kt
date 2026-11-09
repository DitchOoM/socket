package com.ditchoom.socket.quic

import kotlin.coroutines.CoroutineContext

internal actual fun spikeAcceptedDriverContext(default: CoroutineContext): CoroutineContext = default

internal actual fun spikeHandlerContext(
    driverLoop: CoroutineContext,
    default: CoroutineContext,
): CoroutineContext = default
