package com.ditchoom.socket

actual fun ClientSocket.Companion.allocate(config: TransportConfig): ClientToServerSocket =
    throw UnsupportedOperationException("Sockets are not supported in WASM")

actual fun ServerSocket.Companion.allocate(config: TransportConfig): ServerSocket =
    throw UnsupportedOperationException("Sockets are not supported in WASM")
