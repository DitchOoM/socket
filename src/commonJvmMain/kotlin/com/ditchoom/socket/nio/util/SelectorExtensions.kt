package com.ditchoom.socket.nio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Selector
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

suspend fun Selector.aSelect(timeout: Duration): Int {
    val selector = this
    return withContext(Dispatchers.IO) {
        suspendCancellableCoroutine<Int> {
            try {
                it.resume(select(timeout.inWholeMilliseconds))
            } catch (e: Throwable) {
                if (e is AsynchronousCloseException && it.isCancelled) {
                    selector.close()
                    return@suspendCancellableCoroutine
                }
                it.resumeWithException(e)
            }
        }
    }
}

suspend fun Selector.aClose() = suspendCoroutine<Unit> {
    try {
        it.resume(close())
    } catch (e: Throwable) {
        it.resumeWithException(e)
    }
}
