@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Apple/Native: read `QUIC_QLOG_DIR` via POSIX `getenv` (identical to the linux actual). */
internal actual fun qlogDir(): String? = getenv("QUIC_QLOG_DIR")?.toKString()?.takeIf { it.isNotBlank() }
