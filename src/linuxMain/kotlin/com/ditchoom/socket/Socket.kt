package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone,
): ClientToServerSocket {
    TODO("Linux socket support is not yet implemented. Consider using JVM target for now.")
}

actual fun ServerSocket.Companion.allocate(allocationZone: AllocationZone): ServerSocket {
    TODO("Linux socket support is not yet implemented. Consider using JVM target for now.")
}
