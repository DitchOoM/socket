package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    bufferFactory: () -> PlatformBuffer
): ClientToServerSocket = NWClientSocketWrapper(tls)

actual fun ServerSocket.Companion.allocate(
    bufferFactory: () -> PlatformBuffer
): ServerSocket =
    NWServerWrapper()
