package com.ditchoom.socket

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun connect(
    tls: Boolean,
    tcpOptions: Options,
): Socket {
    var netSocket: Socket? = null
    var throwable: Throwable? = null
    try {
        suspendCancellableCoroutine<Unit> {
            val socket =
                if (tls) {
                    Tls.connect(tcpOptions) {
                        it.resume(Unit)
                    }
                } else {
                    Net.connect(tcpOptions) {
                        it.resume(Unit)
                    }
                }
            socket.on("error") { e ->
                if (!it.isCompleted) {
                    if (e.toString().contains("getaddrinfo")) {
                        it.resumeWithException(
                            SocketException(tcpOptions.host ?: "unknown host"),
                        )
                    } else {
                        it.resumeWithException(SocketException("Failed to connect: $e"))
                    }
                }
            }
            netSocket = socket
            it.invokeOnCancellation { throwableLocal ->
                socket.end { }
                socket.destroy()
                throwable = throwableLocal
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

suspend fun Socket.write(buffer: Uint8Array) {
    suspendCancellableCoroutine {
        write(buffer) {
            it.resume(Unit)
        }
        it.invokeOnCancellation {
            end { }
            destroy()
        }
    }
}
