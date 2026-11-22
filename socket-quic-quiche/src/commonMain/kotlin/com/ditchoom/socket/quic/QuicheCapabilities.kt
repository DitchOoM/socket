package com.ditchoom.socket.quic

/**
 * What every quiche-backed connection declares to its callers. All three bindings —
 * FFM, JNI, cinterop — pass `quiche_conn_stream_send` / `quiche_conn_dgram_send` a raw
 * address and a length, so a caller-supplied buffer must expose one; there is no copy at the
 * boundary on any platform. Stated once here, because it is a property of the engine binding, not
 * of the platform.
 */
internal val quicheCapabilities: QuicCapabilities = QuicCapabilities(requiresNativeMemoryBuffers = true)
