package com.ditchoom.socket.nio2.util

import kotlinx.coroutines.CancellableContinuation
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.Channel
import java.nio.channels.NetworkChannel

fun Channel.blockingClose() {
    try {
        close()
    } catch (ex: Throwable) {
        // Specification says that it is Ok to call it any time, but reality is different,
        // so we have just to ignore exception
    }
}

fun AsynchronousFileChannel.closeOnCancel(cont: CancellableContinuation<*>) {
    cont.invokeOnCancellation {
        blockingClose()
    }
}

fun NetworkChannel.closeOnCancel(cont: CancellableContinuation<*>) {
    cont.invokeOnCancellation {
        blockingClose()
    }
}
