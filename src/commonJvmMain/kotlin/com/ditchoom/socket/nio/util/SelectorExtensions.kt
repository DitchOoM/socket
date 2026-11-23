@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.nio.util

import com.ditchoom.socket.WithinDeadline
import com.ditchoom.socket.withinDeadline
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Selector
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

suspend fun Selector.aSelect(timeout: Duration): Int {
    val selector = this
    // The deadline elapsing with no key ready reports "no selection" (0): `Selector.select` translates a
    // 0 count into the uniform SocketTimeoutException (RFC_READ_TIMEOUT_CONTRACT §4.1, Axis 3). Only this
    // call's own deadline does; a caller's enclosing timeout or any other cancellation propagates.
    val selected =
        withinDeadline(timeout) {
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
    return when (selected) {
        is WithinDeadline.Completed -> selected.value
        WithinDeadline.Expired -> 0
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
