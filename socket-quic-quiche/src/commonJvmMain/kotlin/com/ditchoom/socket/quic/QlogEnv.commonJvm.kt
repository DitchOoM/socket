package com.ditchoom.socket.quic

/**
 * JVM/Android: the `quic.qlog.dir` system property, else the `QUIC_QLOG_DIR` environment variable.
 *
 * The property wins because it is the more specific instruction — a caller that set it did so for
 * *this* process, while the environment is ambient and inherited. It also reaches two places the
 * environment cannot: an Android instrumentation run, whose `-e` arguments are extras rather than
 * environment (see [qlogDir]), and a Gradle test JVM, which can be given `-Dquic.qlog.dir=…` without
 * restarting the daemon with a new environment.
 */
internal actual fun qlogDir(): String? = (System.getProperty("quic.qlog.dir") ?: System.getenv("QUIC_QLOG_DIR"))?.takeIf { it.isNotBlank() }
