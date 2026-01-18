package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NodeServerSocket : ServerSocket {
    private var server: Server? = null

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val server = Net.createServer()
        val flow =
            callbackFlow {
                server.on<Socket>("connection") { clientSocket ->
                    val nodeSocket = NodeSocket()
                    nodeSocket.isClosed = false
                    nodeSocket.netSocket = clientSocket
                    clientSocket.on("data") { data ->
                        val result = int8ArrayOf(data)
                        val buffer = JsBuffer(result)
                        buffer.position(result.length)
                        buffer.resetForRead()
                        nodeSocket.incomingMessageChannel.trySend(SocketDataRead(buffer, result.length))
                    }
                    trySend(nodeSocket).getOrThrow()
                }
                server.on("close") {
                    channel.close()
                }
                awaitClose { server.close { } }
            }

        server.listenSuspend(port, host, backlog)
        this@NodeServerSocket.server = server
        return flow
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun int8ArrayOf(
        @Suppress("UNUSED_PARAMETER") obj: Any,
    ): Int8Array =
        js(
            """
            if (Buffer.isBuffer(obj)) {
                return new Int8Array(obj.buffer)
            } else {
                return new Int8Array(Buffer.from(obj).buffer)
            }
        """,
        ) as Int8Array

    override fun isListening() = server?.listening ?: false

    override fun port() = server?.address()?.port ?: -1

    override suspend fun close() {
        val server = server ?: return
        if (!isListening()) return
        suspendCoroutine {
            server.close { it.resume(Unit) }
        }
    }
}

suspend fun Server.listenSuspend(
    port: Int,
    host: String?,
    backlog: Int,
) {
    suspendCancellableCoroutine {
        if (host != null && port != -1) {
            listen(port, host, backlog) {
                if (!it.isCompleted) {
                    it.resume(Unit)
                }
            }
        } else if (port != -1) {
            listen(port, backlog = backlog) {
                if (!it.isCompleted) {
                    it.resume(Unit)
                }
            }
        } else if (host != null) {
            listen(host = host, backlog = backlog) {
                if (!it.isCompleted) {
                    it.resume(Unit)
                }
            }
        } else {
            listen(backlog = backlog) {
                if (!it.isCompleted) {
                    it.resume(Unit)
                }
            }
        }
        it.invokeOnCancellation { close { } }
    }
}
