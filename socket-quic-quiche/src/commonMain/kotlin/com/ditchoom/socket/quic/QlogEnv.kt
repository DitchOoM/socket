package com.ditchoom.socket.quic

/**
 * The directory to write per-connection qlog (`.sqlog`) traces to, read from the `QUIC_QLOG_DIR`
 * environment variable, or `null` when it is unset/blank (the common case — qlog is off).
 *
 * Diagnostics seam: [QuicheDriver] reads this once per connection to decide whether to enable
 * quiche's qlog. Kept as an `expect`/`actual` env reader because Kotlin has no multiplatform
 * `getenv` — JVM/Android use `System.getenv`, Linux/Native uses POSIX `getenv`. Returns `null` on
 * any backend with no environment surface, so qlog simply stays disabled there.
 */
internal expect fun qlogDir(): String?
