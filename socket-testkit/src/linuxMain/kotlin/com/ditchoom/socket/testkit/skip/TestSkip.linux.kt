@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.testkit.skip

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun testkitEnv(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }
