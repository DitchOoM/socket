@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Linux/Native: read `QUIC_QLOG_DIR` via POSIX `getenv`. */
internal actual fun qlogDir(): String? = getenv("QUIC_QLOG_DIR")?.toKString()?.takeIf { it.isNotBlank() }
