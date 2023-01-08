package com.ditchoom.socket.nio2.util

import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun openAsyncServerSocketChannel(group: AsynchronousChannelGroup? = null): AsynchronousServerSocketChannel =
    suspendCoroutine { continuation ->
        try {
            continuation.resume(AsynchronousServerSocketChannel.open(group))
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
