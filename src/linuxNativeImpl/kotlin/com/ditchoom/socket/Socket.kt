package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

/**
 * Linux implementation using io_uring for async I/O with OpenSSL TLS support.
 */
actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone,
): ClientToServerSocket = LinuxClientSocket(useTls = tls)

/**
 * Linux server socket implementation using io_uring for async accept.
 */
actual fun ServerSocket.Companion.allocate(
    allocationZone: AllocationZone,
): ServerSocket = LinuxServerSocket()
