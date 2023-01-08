package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NodeServerSocket : ServerSocket {
    var server: Server? = null
    private lateinit var scope: CoroutineScope

    override fun setScope(scope: CoroutineScope) {
        this.scope = scope
    }

    override suspend fun start(
        port: Int,
        host: String?,
        socketOptions: SocketOptions?,
        backlog: Int,
        acceptedClient: suspend (ClientSocket) -> Unit
    ): SocketOptions {
        val server = withContext(Dispatchers.Default) {
            Net.createServer { clientSocket ->
                val nodeSocket = NodeSocket()
                nodeSocket.isClosed = false
                nodeSocket.netSocket = clientSocket
                clientSocket.on("data") { data ->
                    val result = uint8ArrayOf(data)
                    val buffer = JsBuffer(result)
                    buffer.setPosition(result.length)
                    buffer.setLimit(result.length)
                    nodeSocket.incomingMessageChannel.trySend(SocketDataRead(buffer, result.length))
                }

                scope.launch {
                    acceptedClient(nodeSocket)
                }
            }
        }

        server.listenSuspend(port, host, backlog)
        this.server = server
        return socketOptions ?: SocketOptions()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun uint8ArrayOf(@Suppress("UNUSED_PARAMETER") obj: Any): Uint8Array {
        return js(
            """
            if (Buffer.isBuffer(obj)) {
                return new Uint8Array(obj.buffer)
            } else {
                return new Uint8Array(Buffer.from(obj).buffer)
            }
        """
        ) as Uint8Array
    }

    override fun isOpen() = server?.listening ?: false

    override fun port() = server?.address()?.port ?: -1

    override suspend fun close() {
        val server = server ?: return
        if (!isOpen()) return
        suspendCoroutine {
            server.close { it.resume(Unit) }
        }
    }
}

suspend fun Server.listenSuspend(port: Int, host: String?, backlog: Int) {
    suspendCancellableCoroutine {
        if (host != null && port != -1) {
            listen(port, host, backlog) {
                it.resume(Unit)
            }
        } else if (port != -1) {
            listen(port, backlog = backlog) {
                it.resume(Unit)
            }
        } else if (host != null) {
            listen(host = host, backlog = backlog) {
                it.resume(Unit)
            }
        } else {
            listen(backlog = backlog) {
                it.resume(Unit)
            }
        }
        it.invokeOnCancellation { close { } }
    }
}
