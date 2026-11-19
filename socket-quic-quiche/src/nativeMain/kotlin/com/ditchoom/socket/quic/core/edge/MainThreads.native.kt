package com.ditchoom.socket.quic.core.edge

/** A Kotlin/Native main thread is known by its main dispatcher. */
internal actual val mainThreadsOfThisRuntime: MainThreads = MainThreads.ByDispatcher

/** Kotlin/Native knows no main thread of its own: no value of this type exists. */
internal actual class MainThread private constructor(
    private val impossible: Nothing,
) {
    actual fun isCurrent(): Boolean = impossible
}
