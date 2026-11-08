package com.ditchoom.socket

import kotlinx.coroutines.CompletableDeferred
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private val net: dynamic = js("require('net')")

internal actual suspend fun connectRawTcpPeer(port: Int): RawTcpPeer {
    val connected = CompletableDeferred<Unit>()
    val socket: dynamic = net.createConnection(port, "127.0.0.1")
    socket.once("connect") { connected.complete(Unit) }
    socket.once("error") { e: dynamic -> connected.completeExceptionally(IllegalStateException("connect failed: ${e.message}")) }
    connected.await()
    return object : RawTcpPeer {
        override suspend fun write(bytes: ByteArray) {
            val written = CompletableDeferred<Unit>()
            val view = Uint8Array(bytes.unsafeCast<Int8Array>().buffer)
            socket.write(view) { written.complete(Unit) }
            written.await()
        }

        override suspend fun close() {
            socket.end()
        }

        // Node 16.17+/18.3+: SO_LINGER 0 and destroy, so the connection ends with a RST.
        override suspend fun reset() {
            socket.resetAndDestroy()
        }
    }
}
