package com.ditchoom.socket

/**
 * Linux implementation using io_uring for async I/O with OpenSSL TLS support.
 */
actual fun ClientSocket.Companion.allocate(): ClientToServerSocket = LinuxClientSocket()

/**
 * Linux server socket implementation using io_uring for async accept.
 */
actual fun ServerSocket.Companion.allocate(): ServerSocket = LinuxServerSocket()
