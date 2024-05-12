package com.ditchoom.socket

import cocoapods.SocketWrapper.ServerSocketListenerWrapper
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
class NWServerWrapper : ServerSocket {
    private var server: ServerSocketListenerWrapper? = null

    @OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)
    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        val server = ServerSocketListenerWrapper(port.convert(), host, backlog.convert())
        val flow =
            callbackFlow {
                server.assignAcceptedCallbackListenerWithAcceptedClient { socketWrapper ->
                    val nwSocketWrapper = NWSocketWrapper()
                    nwSocketWrapper.socket = socketWrapper
                    trySendBlocking(nwSocketWrapper).getOrThrow()
                }
                server.assignCloseCallbackWithCb {
                    channel.close()
                }
                awaitClose { server.stopListeningForInboundConnectionsWithCb {} }
            }

        this@NWServerWrapper.server =
            suspendCancellableCoroutine {
                server.startWithCompletionHandler { serverSocketListenerWrapper, errorString, _, _, _ ->
                    if (errorString != null) {
                        it.resumeWithException(SocketException(errorString))
                    } else if (serverSocketListenerWrapper != null) {
                        it.resume(serverSocketListenerWrapper)
                    } else {
                        it.resumeWithException(IllegalStateException("Failed to get a valid socket or error message"))
                    }
                }
            }
        return flow
    }

    override fun isListening(): Boolean = server?.isOpen() ?: false

    @OptIn(UnsafeNumber::class)
    override fun port(): Int = server?.port()?.toInt() ?: -1

    override suspend fun close() {
        val server = server ?: return
        suspendCoroutine {
            var isDone = false

            fun completion() {
                if (!isDone) {
                    it.resume(Unit)
                    isDone = true
                }
            }
            server.assignCloseCallbackWithCb {
                completion()
            }
            server.stopListeningForInboundConnectionsWithCb {
                completion()
            }
        }
    }
}
