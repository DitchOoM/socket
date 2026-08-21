package com.ditchoom.socket.quic

/**
 * The directory to write per-connection qlog (`.sqlog`) traces to, or `null` when qlog is off (the
 * common case).
 *
 * Diagnostics seam: [QuicheDriver] reads this once per connection to decide whether to enable
 * quiche's qlog. Kept as an `expect`/`actual` reader because Kotlin has no multiplatform `getenv` —
 * JVM/Android use `System.getenv`, Linux/Native use POSIX `getenv`. Returns `null` on any backend
 * with no environment surface, so qlog simply stays disabled there.
 *
 * The JVM/Android actual also honours the **`quic.qlog.dir` system property**, which takes
 * precedence, because an environment variable is not reachable from every place a trace is worth
 * taking: an Android instrumentation run (`am instrument -e …`) passes *extras*, not environment, so
 * on a device — the one place a real Wi-Fi↔cellular handoff can be recorded — `QUIC_QLOG_DIR` can
 * never be set. #437 was found on such a walk and stalled for want of frame-level evidence.
 */
internal expect fun qlogDir(): String?
