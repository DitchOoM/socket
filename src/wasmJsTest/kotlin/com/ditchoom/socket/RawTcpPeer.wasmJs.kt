package com.ditchoom.socket

// A browser has no TCP sockets; tests using this skip when TCP is not a capability.
internal actual suspend fun connectRawTcpPeer(port: Int): RawTcpPeer = throw UnsupportedOperationException("no TCP in the browser")
