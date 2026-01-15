package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone,
): ClientToServerSocket {
    TODO("WASM socket support is not yet implemented. WebSockets may be available in browser environments.")
}

actual fun ServerSocket.Companion.allocate(allocationZone: AllocationZone): ServerSocket {
    TODO("WASM server socket support is not yet implemented.")
}
