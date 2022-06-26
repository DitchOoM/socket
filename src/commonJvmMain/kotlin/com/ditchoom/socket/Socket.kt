package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(tls: Boolean, zone: AllocationZone): ClientToServerSocket = try {
    AsyncClientSocket(tls, zone)
} catch (t: Throwable) {
    NioClientSocket(tls, zone, false)
}

actual fun ServerSocket.Companion.allocate(tls: Boolean, zone: AllocationZone): ServerSocket = AsyncServerSocket(tls, zone)
