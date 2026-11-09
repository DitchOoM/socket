package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where this module's public suspend functions do work that blocks a thread (key generation reading
 * the OS entropy source), so it never runs on the caller's thread, which may be the Android main
 * thread or a desktop UI thread. `Dispatchers.IO` wherever one exists; JS has one thread and nothing to
 * hand the work to.
 */
internal expect val blockingWork: CoroutineDispatcher

/**
 * Runs [block] on [blockingWork] and returns what it built. A caller cancelled just as [block] finishes
 * never receives the result (`withContext` discards it), so it is handed to [onAbandoned], which closes
 * what the caller would otherwise have owned.
 */
internal suspend inline fun <T> buildOffTheCallersThread(
    crossinline onAbandoned: (T) -> Unit,
    crossinline block: () -> T,
): T {
    var produced: Result<T>? = null
    try {
        return withContext(blockingWork) { block().also { produced = Result.success(it) } }
    } catch (e: CancellationException) {
        produced?.getOrNull()?.let { onAbandoned(it) }
        throw e
    }
}
