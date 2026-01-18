package com.ditchoom.socket

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

// JavaScript setTimeout/clearTimeout
private fun jsSetTimeout(
    handler: () -> Unit,
    timeout: Int,
): dynamic = js("setTimeout(handler, timeout)")

private fun jsClearTimeout(handle: dynamic) {
    js("clearTimeout(handle)")
}

suspend fun connect(
    tls: Boolean,
    tcpOptions: Options,
    timeout: Duration? = null,
): Socket {
    var netSocket: Socket? = null
    var throwable: Throwable? = null
    try {
        suspendCancellableCoroutine<Unit> { cont ->
            var timeoutHandle: dynamic = null

            val socket =
                if (tls) {
                    Tls.connect(tcpOptions) {
                        if (timeoutHandle != null) jsClearTimeout(timeoutHandle)
                        if (!cont.isCompleted) {
                            cont.resume(Unit)
                        }
                    }
                } else {
                    Net.connect(tcpOptions) {
                        if (timeoutHandle != null) jsClearTimeout(timeoutHandle)
                        if (!cont.isCompleted) {
                            cont.resume(Unit)
                        }
                    }
                }

            // Set connection timeout if specified
            if (timeout != null) {
                timeoutHandle =
                    jsSetTimeout({
                        if (!cont.isCompleted) {
                            socket.destroy()
                            cont.resumeWithException(SocketException("Connection timeout after $timeout"))
                        }
                    }, timeout.inWholeMilliseconds.toInt())
            }

            socket.on("error") { e ->
                if (timeoutHandle != null) jsClearTimeout(timeoutHandle)
                if (!cont.isCompleted) {
                    if (e.toString().contains("getaddrinfo")) {
                        cont.resumeWithException(
                            SocketUnknownHostException(tcpOptions.host ?: "unknown host"),
                        )
                    } else {
                        cont.resumeWithException(SocketException("Failed to connect: $e"))
                    }
                }
            }
            netSocket = socket
            cont.invokeOnCancellation { throwableLocal ->
                if (timeoutHandle != null) jsClearTimeout(timeoutHandle)
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
