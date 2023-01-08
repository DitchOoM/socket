package com.ditchoom.socket

import cocoapods.SocketWrapper.ServerSocketListenerWrapper
import cocoapods.SocketWrapper.SocketWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NWServerWrapper : ServerSocket {
    private var server: ServerSocketListenerWrapper? = null
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
        val acceptedClientCallback = { acceptedClient: SocketWrapper? ->
            println("Incoming cl!ent $acceptedClient")
            val socketWrapper = NWSocketWrapper()
            socketWrapper.socket = acceptedClient

            println("launch")
            val j = scope.launch {
                println("launch accept")
                acceptedClient(socketWrapper)
                println("launched accept")
            }
            println("launched $j")
        }
        server = suspendCancellableCoroutine {
            val server = ServerSocketListenerWrapper()
            server.startWithPort(
                port.toLong(),
                host,
                backlog.toLong(),
                acceptedClientCallback
            ) { serverSocketWrapper, errorString, isPosixError, isDnsError, isTlsError ->
                println("server started: $serverSocketWrapper $errorString")
                if (errorString != null) {
                    it.resumeWithException(SocketException(errorString))
                } else if (serverSocketWrapper != null) {
                    it.resume(serverSocketWrapper)
                } else {
                    it.resumeWithException(IllegalStateException("Failed to get a valid socket or error message"))
                }
            }
            it.invokeOnCancellation {
                server.stopListeningForInboundConnections()
            }
        }

        return socketOptions ?: SocketOptions()
    }

    override fun isOpen(): Boolean = server?.isOpen() ?: false

    override fun port(): Int = server?.port()?.toInt() ?: -1

    override suspend fun close() {
        server?.stopListeningForInboundConnections()
    }
}
