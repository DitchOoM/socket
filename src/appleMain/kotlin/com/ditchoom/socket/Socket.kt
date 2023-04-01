package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone
): ClientToServerSocket = NWClientSocketWrapper(tls)

actual fun ServerSocket.Companion.allocate(
    allocationZone: AllocationZone
): ServerSocket =
    NWServerWrapper()
