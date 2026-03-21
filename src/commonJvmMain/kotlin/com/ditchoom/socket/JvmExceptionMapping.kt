package com.ditchoom.socket

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import javax.net.ssl.SSLHandshakeException

/**
 * Maps a JVM platform exception to the appropriate [SocketException] subtype.
 *
 * This is the single point of truth for JVM → SocketException mapping. All async handlers,
 * completion callbacks, and suspend wrappers should call this rather than passing raw
 * platform exceptions to callers.
 *
 * @param ex the original JVM exception
 * @param host optional hostname context (for connection-related errors)
 * @param port optional port context (for connection-related errors)
 */
internal fun wrapJvmException(
    ex: Throwable,
    host: String? = null,
    port: Int = -1,
): SocketException {
    // Already wrapped — pass through
    if (ex is SocketException) return ex

    return when (ex) {
        is ConnectException -> {
            val msg = ex.message?.lowercase() ?: ""
            when {
                msg.contains("refused") ->
                    SocketConnectionException.Refused(host, port, ex, ex.message)
                msg.contains("timed out") || msg.contains("timeout") ->
                    SocketTimeoutException("Connection timed out: $host:$port", host, port, ex)
                msg.contains("network is unreachable") || msg.contains("enetunreach") ->
                    SocketConnectionException.NetworkUnreachable(ex.message ?: "Network unreachable", ex)
                msg.contains("no route to host") || msg.contains("ehostunreach") ->
                    SocketConnectionException.HostUnreachable(ex.message ?: "Host unreachable", ex)
                else ->
                    SocketIOException(ex.message ?: "Connection failed", ex)
            }
        }
        is UnknownHostException ->
            SocketUnknownHostException(host ?: ex.message, cause = ex)
        is SocketTimeoutException ->
            com.ditchoom.socket.SocketTimeoutException(
                ex.message ?: "Socket operation timed out",
                host,
                port,
                ex,
            )
        is AsynchronousCloseException ->
            SocketClosedException.General("Socket closed during operation", ex)
        is ClosedChannelException ->
            SocketClosedException.General("Socket is closed", ex)
        is SSLHandshakeException ->
            SSLHandshakeFailedException(ex.message ?: "TLS handshake failed", ex)
        is javax.net.ssl.SSLException ->
            SSLProtocolException(ex.message ?: "TLS error", ex)
        is java.io.IOException -> {
            val msg = ex.message?.lowercase() ?: ""
            when {
                msg.contains("broken pipe") ->
                    SocketClosedException.BrokenPipe("Broken pipe", ex)
                msg.contains("connection reset") ->
                    SocketClosedException.ConnectionReset("Connection reset", ex)
                msg.contains("closed") ->
                    SocketClosedException.General(ex.message ?: "Socket closed", ex)
                else ->
                    SocketIOException(ex.message ?: "I/O error", ex)
            }
        }
        else ->
            SocketIOException(ex.message ?: "Socket error", ex)
    }
}
