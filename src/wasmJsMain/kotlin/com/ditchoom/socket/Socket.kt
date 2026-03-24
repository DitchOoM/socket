package com.ditchoom.socket

actual fun ClientSocket.Companion.allocate(): ClientToServerSocket =
    throw UnsupportedOperationException("Sockets are not supported in WASM")

actual fun ServerSocket.Companion.allocate(): ServerSocket = throw UnsupportedOperationException("Sockets are not supported in WASM")
