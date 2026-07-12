@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.nio.util

import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Selector
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

suspend fun Selector.aSelect(timeout: Duration): Int {
    val selector = this
    return try {
        withTimeout(timeout) {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
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
                }
                0
            }
        }
    } catch (e: TimeoutCancellationException) {
        // The deadline elapsed with no key ready. Report "no selection" (0) rather than leaking a
        // raw TimeoutCancellationException: `Selector.select` translates a 0 count into the uniform
        // SocketTimeoutException (RFC_READ_TIMEOUT_CONTRACT §4.1, Axis 3). Only *our* withTimeout's
        // own expiry produces TimeoutCancellationException; an external cancellation arrives as a
        // plain CancellationException and still propagates.
        0
    }
}

suspend fun Selector.aClose() =
    suspendCoroutine<Unit> {
        try {
            it.resume(close())
        } catch (e: Throwable) {
            it.resumeWithException(wrapJvmException(e))
        }
    }
