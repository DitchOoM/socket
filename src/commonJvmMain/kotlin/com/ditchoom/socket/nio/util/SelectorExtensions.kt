package com.ditchoom.socket.nio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Selector
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

suspend fun Selector.aSelect(timeout: Duration): Int {
    val selector = this
    return withTimeout(timeout) {
        withContext(Dispatchers.Default) {
            while (isActive) {
                try {
                    val keys = selectNow()
                    if (keys > 0) {
                        return@withContext keys
                    }
                } catch (e: Throwable) {
                    if (e is AsynchronousCloseException) {
                        selector.close()
                        return@withContext 0
                    }
                }
                yield()
            }
            0
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
