package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun asyncClientSocket(zone: AllocationZone): ClientToServerSocket = AsyncClientSocket()

actual fun clientSocket(zone: AllocationZone, blocking: Boolean): ClientToServerSocket =
    NioClientSocket(zone, blocking)

actual fun asyncServerSocket(zone: AllocationZone): ServerSocket = AsyncServerSocket(zone)
