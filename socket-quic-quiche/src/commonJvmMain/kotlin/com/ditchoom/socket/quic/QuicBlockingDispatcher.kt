package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dispatcher for the blocking JDK calls inside our NIO selector loops
 * ([NioUdpChannel.receive] and [JvmQuicServer.receiveLoop]).
 *
 * On JDK 21+: virtual threads via `Executors.newVirtualThreadPerTaskExecutor`.
 * Each blocking `Selector.select()` runs on its own virtual thread.
 * Virtual threads are essentially free to spawn (a few KB of stack each)
 * and don't pin a platform thread, so concurrent QUIC clients each have
 * their own selector loop without competing for slots in the platform-
 * thread IO pool. Even a leaked select() (the failure mode this fix
 * targets) costs a virtual thread, not a precious platform thread.
 *
 * On JDK < 21: falls back to [Dispatchers.IO]. Functionally equivalent;
 * subject to the platform-thread pool size ceiling (default 64).
 *
 * Detection is via reflection on `java.util.concurrent.Executors` so the
 * compiled bytecode doesn't reference JDK-21-only symbols directly. The
 * bytecode loads cleanly on every JDK ≥ 8 (Android included) and the
 * virtual-thread branch is taken only when the method actually exists at
 * runtime. Mirrors the FFI-vs-JNI runtime-detection pattern already in
 * this module ([FfmQuicheApi] vs [JniQuicheApi]).
 *
 * Lifetime: process-wide singleton. The underlying [ExecutorService] is
 * never explicitly shut down — JVM termination releases it. There is no
 * cost to keeping it idle (virtual-thread executors don't pre-allocate).
 */
internal val quicBlockingDispatcher: CoroutineDispatcher by lazy {
    detectVirtualThreadDispatcher() ?: Dispatchers.IO
}

private fun detectVirtualThreadDispatcher(): CoroutineDispatcher? =
    try {
        val method = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
        val executor = method.invoke(null) as ExecutorService
        executor.asCoroutineDispatcher()
    } catch (_: NoSuchMethodException) {
        // Pre-JDK-21 — virtual threads not available.
        null
    } catch (_: Throwable) {
        // Any other reflective failure (security manager, Android quirks,
        // etc.) — fall back to the regular IO dispatcher.
        null
    }
