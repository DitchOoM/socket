package com.ditchoom.socket.transport

import kotlinx.coroutines.CoroutineDispatcher

/**
 * What the writer of a [CodecConnection], [CodecSender] or [TypedMuxView] built by one of their deprecated
 * constructors (which pick a writer scope for the caller) runs on.
 *
 * A writer is woken from threads that are not the coroutine scheduler's CPU workers: a caller's own thread
 * handing it a message, and the transport's I/O threads (a QUIC event loop, an NIO completion thread)
 * finishing its write. On the JVM, kotlinx.coroutines 1.11.0 can lose such a wakeup for a
 * `Dispatchers.Default` coroutine (kotlinx.coroutines #4491), stalling the writer until some unrelated
 * dispatch. So on JVM/Android this is a view of `Dispatchers.IO` as wide as `Dispatchers.Default`, whose
 * tasks any woken worker runs; elsewhere it is `Dispatchers.Default`, which that race does not affect.
 *
 * The primary constructors take the caller's scope, and with it the caller's dispatcher.
 */
internal expect val codecWriterDispatcher: CoroutineDispatcher
