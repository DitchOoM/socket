@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.pthread_self

internal actual fun currentThreadId(): Long = pthread_self().toLong()
