package com.ditchoom.socket

import cocoapods.SocketWrapper.ServerSocketListenerWrapper
import cocoapods.SocketWrapper.ServerSocketWrapper
import cocoapods.SocketWrapper.SocketWrapper
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NWServerWrapper(private val coroutineScope: CoroutineScope) : ServerSocket {
    private var server: ServerSocketListenerWrapper? = null

    override suspend fun start(
        port: Int,
        host: String?,
        socketOptions: SocketOptions?,
        backlog: Int,
        acceptedClient: suspend (ClientSocket) -> Unit
    ): SocketOptions {
        val acceptedClientCallback: (ServerSocketWrapper?) -> Unit =
            { socketWrapper: SocketWrapper? ->
                val nwSocketWrapper = NWSocketWrapper()
                nwSocketWrapper.socket = socketWrapper
                scope.launch { acceptedClient(nwSocketWrapper) }
            }
        server = suspendCancellableCoroutine {
            val server = ServerSocketListenerWrapper()
            server.startWithPort(
                port.convert(),
                host,
                backlog.convert(),
                acceptedClientCallback
            ) { serverSocketWrapper, errorString, isPosixError, isDnsError, isTlsError ->
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
