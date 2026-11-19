package com.ditchoom.socket.quic.core.edge

/** A Kotlin/Native main thread is known by its main dispatcher. */
internal actual val mainThreadsOfThisRuntime: MainThreads = MainThreads.ByDispatcher
