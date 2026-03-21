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
                            cont.resumeWithException(
                                SocketTimeoutException(
                                    "Connection timeout after $timeout",
                                    tcpOptions.host,
                                    tcpOptions.port,
                                ),
                            )
                        }
                    }, timeout.inWholeMilliseconds.toInt())
            }

            socket.on("error") { e ->
                if (timeoutHandle != null) jsClearTimeout(timeoutHandle)
                if (!cont.isCompleted) {
                    cont.resumeWithException(wrapNodeError(e, tcpOptions.host))
                }
            }
            netSocket = socket
            cont.invokeOnCancellation { throwableLocal ->
                if (timeoutHandle != null) jsClearTimeout(timeoutHandle)
                socket.removeAllListeners()
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
            removeAllListeners()
            end { }
            destroy()
        }
    }
}

/**
 * Maps a Node.js error object to the appropriate [SocketException] subtype.
 */
internal fun wrapNodeError(
    e: dynamic,
    host: String?,
): SocketException {
    val errorStr = e.toString() as String
    val code = try { e.code as? String } catch (_: Throwable) { null }

    return when {
        code == "ECONNREFUSED" ->
            SocketConnectionException.Refused(host, 0, platformError = errorStr)
        code == "ETIMEDOUT" ->
            SocketTimeoutException("Connection timed out: $errorStr", host)
        code == "ECONNRESET" ->
            SocketClosedException.ConnectionReset("Connection reset: $errorStr")
        code == "EPIPE" ->
            SocketClosedException.BrokenPipe("Broken pipe: $errorStr")
        code == "ENETUNREACH" ->
            SocketConnectionException.NetworkUnreachable(errorStr)
        code == "EHOSTUNREACH" ->
            SocketConnectionException.HostUnreachable(errorStr)
        errorStr.contains("getaddrinfo") ->
            SocketUnknownHostException(host ?: "unknown host")
        errorStr.contains("ERR_TLS") || errorStr.contains("SSL") ->
            SSLProtocolException(errorStr)
        else ->
            SocketIOException("Failed to connect: $errorStr")
    }
}
