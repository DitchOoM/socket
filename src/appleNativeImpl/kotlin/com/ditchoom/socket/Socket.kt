package com.ditchoom.socket

/**
 * Allocates a client socket using Apple's Network.framework.
 *
 * Supports zero-copy data transfer with native NSData buffers.
 */
actual fun ClientSocket.Companion.allocate(): ClientToServerSocket = NWClientSocketWrapper()

/**
 * Allocates a server socket using Apple's Network.framework.
 *
 * Returns zero-copy socket wrappers for accepted connections.
 */
actual fun ServerSocket.Companion.allocate(): ServerSocket = NWServerWrapper()
