package com.ditchoom.socket.quic

/** JVM/Android: read `QUIC_QLOG_DIR` from the process environment. */
internal actual fun qlogDir(): String? = System.getenv("QUIC_QLOG_DIR")?.takeIf { it.isNotBlank() }
