package com.ditchoom.socket.quic

/**
 * Loads the appropriate [QuicheApi] for the current JDK.
 * On JDK 21+, the multi-release JAR shadows this with the FFM version.
 *
 * Process-wide singleton (see QuicheApiLifecycleTest): callers invoke this per connection/server,
 * and the api — including the recv_info guard wrapper, whose lifecycle bookkeeping should span the
 * whole process — must be one held instance, never per-call.
 */
fun loadQuicheApi(): QuicheApi = jniQuicheApi

private val jniQuicheApi: QuicheApi by lazy { maybeGuardRecvInfo(JniQuicheApi) }
