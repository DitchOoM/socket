package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

/**
 * Allocates a client socket using Apple's Network.framework.
 *
 * Supports zero-copy data transfer with native NSData buffers.
 */
actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone,
): ClientToServerSocket = NWClientSocketWrapper(tls)

/**
 * Allocates a server socket using Apple's Network.framework.
 *
 * Returns zero-copy socket wrappers for accepted connections.
 */
actual fun ServerSocket.Companion.allocate(allocationZone: AllocationZone): ServerSocket = NWServerWrapper()
