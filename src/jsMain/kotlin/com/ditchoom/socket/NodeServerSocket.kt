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
    private val acceptedConnections = mutableListOf<Socket>()

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val server = Net.createServer()
        val connections = acceptedConnections
        val flow =
            callbackFlow {
                server.on<Socket>("connection") { clientSocket ->
                    connections.add(clientSocket)
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
                        connections.remove(clientSocket)
                        clientSocket.removeAllListeners()
                        nodeSocket.incomingMessageChannel.close()
                        nodeSocket.isClosed = true
                    }
                    clientSocket.on("error") { _ ->
                        connections.remove(clientSocket)
                        clientSocket.removeAllListeners()
                        nodeSocket.incomingMessageChannel.close()
                        nodeSocket.isClosed = true
                    }
                    trySend(nodeSocket).getOrThrow()
                }
                server.on("close") {
                    channel.close()
                }
                awaitClose {
                    destroyAllConnections()
                    server.removeAllListeners()
                    server.unref()
                    server.close { }
                }
            }

        server.listenSuspend(port, host, backlog)
        this@NodeServerSocket.server = server
        return flow
    }

    private fun destroyAllConnections() {
        acceptedConnections.forEach { socket ->
            socket.removeAllListeners()
            socket.destroy()
            socket.unref()
        }
        acceptedConnections.clear()
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
        destroyAllConnections()
        suspendCoroutine {
            // Don't removeAllListeners before close — the callbackFlow's "close"
            // handler needs to fire to close the channel and complete the flow.
            server.close { it.resume(Unit) }
        }
        server.removeAllListeners()
        server.unref()
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
        cont.invokeOnCancellation {
            removeAllListeners()
            unref()
            close { }
        }
    }
}
