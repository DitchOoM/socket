package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CoroutineScope

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    bufferFactory: () -> PlatformBuffer
): ClientToServerSocket = NWClientSocketWrapper(tls)

actual fun ServerSocket.Companion.allocate(
    scope: CoroutineScope,
    bufferFactory: () -> PlatformBuffer
): ServerSocket =
    NWServerWrapper(scope)
