package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

internal actual suspend fun connectRawTcpPeer(port: Int): RawTcpPeer =
    withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        object : RawTcpPeer {
            override suspend fun write(bytes: ByteArray) =
                withContext(Dispatchers.IO) {
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }

            override suspend fun close() = withContext(Dispatchers.IO) { socket.close() }

            override suspend fun reset() =
                withContext(Dispatchers.IO) {
                    socket.setSoLinger(true, 0)
                    socket.close()
                }
        }
    }
