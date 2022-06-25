package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(zone: AllocationZone): ClientToServerSocket = try {
    AsyncClientSocket(zone)
} catch (t: Throwable) {
    NioClientSocket(zone, false)
}

actual fun ServerSocket.Companion.allocate(zone: AllocationZone): ServerSocket = AsyncServerSocket(zone)
