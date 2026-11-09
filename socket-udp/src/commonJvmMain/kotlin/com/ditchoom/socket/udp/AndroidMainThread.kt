package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Android main thread, or null on a desktop JVM. Looked up once, by reflection, because this
 * source set is shared with the desktop JVM, which has no `android.os.Looper`.
 */
internal val androidMainThread: Thread? =
    runCatching {
        val looperClass = Class.forName("android.os.Looper")
        val mainLooper = looperClass.getMethod("getMainLooper").invoke(null)
        looperClass.getMethod("getThread").invoke(mainLooper) as Thread
    }.getOrNull()

/**
 * Runs [block] on [Dispatchers.IO] when called on the Android main thread, and in place otherwise.
 *
 * Android treats every socket system call on the main thread as a violation, blocking or not: the
 * default thread policy of an app's main thread turns one into a `NetworkOnMainThreadException`. A
 * connect, read or write that would merely start (or try) a non-blocking operation on any other
 * thread therefore has to move when the caller is the main thread. Anywhere else — every desktop JVM
 * thread, and every Android thread but the main one — the check is one reference comparison and the
 * call stays where it is, with no dispatch (the QUIC datapath sends from its own threads, never the main one).
 *
 * A caller cancelled just as [block] finishes on [Dispatchers.IO] never receives its result
 * (`withContext` discards it), so that result is handed to [onAbandoned] — which closes or frees
 * whatever the caller would otherwise have owned.
 *
 * The same helper as the root module's; this module does not depend on it.
 */
internal suspend inline fun <T> offTheAndroidMainThread(
    crossinline onAbandoned: (T) -> Unit = {},
    crossinline block: suspend () -> T,
): T {
    if (androidMainThread == null || Thread.currentThread() !== androidMainThread) return block()
    var produced: Result<T>? = null
    try {
        return withContext(Dispatchers.IO) { block().also { produced = Result.success(it) } }
    } catch (e: CancellationException) {
        produced?.getOrNull()?.let { onAbandoned(it) }
        throw e
    }
}
