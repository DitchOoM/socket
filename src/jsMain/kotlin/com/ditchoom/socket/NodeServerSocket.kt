package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
                    clientSocket.on("close") { _ ->
                        nodeSocket.incomingMessageChannel.close()
                        nodeSocket.isClosed = true
                    }
                    clientSocket.on("error") { _ ->
                        nodeSocket.incomingMessageChannel.close()
                        nodeSocket.isClosed = true
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
                // Zero-copy view into the Node.js Buffer
                return new Int8Array(obj.buffer, obj.byteOffset, obj.byteLength)
            } else {
                var buf = Buffer.from(obj);
                return new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength)
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
    suspendCancellableCoroutine { cont ->
        // Handle errors (e.g., port already in use)
        on<Any>("error") { error ->
            if (!cont.isCompleted) {
                val message = error.asDynamic().message as? String ?: "Server error"
                cont.resumeWithException(SocketException(message))
            }
        }

        if (host != null && port != -1) {
            listen(port, host, backlog) {
                if (!cont.isCompleted) {
                    cont.resume(Unit)
                }
            }
        } else if (port != -1) {
            listen(port, backlog = backlog) {
                if (!cont.isCompleted) {
                    cont.resume(Unit)
                }
            }
        } else if (host != null) {
            listen(host = host, backlog = backlog) {
                if (!cont.isCompleted) {
                    cont.resume(Unit)
                }
            }
        } else {
            listen(backlog = backlog) {
                if (!cont.isCompleted) {
                    cont.resume(Unit)
                }
            }
        }
        cont.invokeOnCancellation { close { } }
    }
}
