package com.ditchoom.socket.quic

import kotlin.coroutines.CoroutineContext

/** SPIKE (perf/owned-loop-spike, never merged): the driver context for a server-accepted connection. */
internal expect fun spikeAcceptedDriverContext(default: CoroutineContext): CoroutineContext

/** SPIKE: where an accepted connection's handler runs. */
internal expect fun spikeHandlerContext(
    driverLoop: CoroutineContext,
    default: CoroutineContext,
): CoroutineContext
