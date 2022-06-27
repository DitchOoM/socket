package com.ditchoom.socket

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


suspend fun connect(tcpOptions: tcpOptions, failureCb: (Socket, Throwable?) -> Unit): Socket {
    var netSocket: Socket? = null
    var throwable: Throwable? = null
    try {
        suspendCancellableCoroutine<Unit> {
            val socket = Net.connect(tcpOptions) {
                it.resume(Unit)
            }
            socket.on("error") { e ->
                console.error("error", e)
                it.resumeWithException(RuntimeException(e.toString()))
            }
            netSocket = socket
            it.invokeOnCancellation { throwableLocal ->
                throwable = throwableLocal
                failureCb(socket, throwable)
            }
        }
    } catch (t: Throwable) {
        val throwableLocal = throwable
        if (throwableLocal != null) {
            throw throwableLocal
        } else {
            throw t
        }
    }
    return netSocket!!
}

suspend fun connect(tcpOptions: TcpSocketConnectOpts): Socket {
    var netSocket: Socket? = null
    suspendCoroutine<Unit> {
        val socket = Net.connect(tcpOptions) {
            it.resume(Unit)
        }
        socket.on("error") { e ->
            it.resumeWithException(RuntimeException(e.toString()))
        }
        netSocket = socket
    }
    return netSocket!!
}

suspend fun Socket.write(buffer: Uint8Array) {
    suspendCoroutine<Unit> {
        write(buffer) {
            it.resume(Unit)
        }
    }
}

suspend fun Socket.close() {
    suspendCoroutine<Unit> {
        end {
            it.resume(Unit)
        }
    }
}