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
    val code =
        try {
            e.code as? String
        } catch (_: Throwable) {
            null
        }

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
        // Node.js surfaces TLS cert validation failures with OpenSSL X.509
        // error codes (DEPTH_ZERO_SELF_SIGNED_CERT etc.) — the error message
        // string for these doesn't always contain "ERR_TLS" or "SSL", so the
        // catch-all below misses them and they leak as SocketIOException.
        // The Tls{Conformance,ValidPath}ConformanceTests cert-rejection cases
        // (tlsHarness{SelfSigned,Expired,UntrustedRoot,WrongHost}FailsWithDefault)
        // assert SSLSocketException specifically — map cert codes here so the
        // cross-platform contract holds on jsNode.
        code in tlsCertErrorCodes ->
            SSLHandshakeFailedException(
                "Certificate validation failed: $errorStr",
                reason = ConnectionFailureReason.TlsBadCertificate,
            )
        code == "ERR_TLS_CERT_ALTNAME_INVALID" ->
            SSLHandshakeFailedException(
                "Hostname mismatch: $errorStr",
                reason = ConnectionFailureReason.TlsBadCertificate,
            )
        errorStr.contains("ERR_TLS") || errorStr.contains("SSL") ->
            SSLProtocolException(errorStr)
        else ->
            SocketIOException("Failed to connect: $errorStr")
    }
}

/**
 * OpenSSL X.509 error codes that Node.js surfaces verbatim via `error.code`
 * when cert chain validation fails (Node `tls.connect` with
 * `rejectUnauthorized: true`). The values come from openssl/x509.h —
 * canonical list at https://nodejs.org/api/tls.html#x509-certificate-error-codes.
 */
private val tlsCertErrorCodes =
    setOf(
        "DEPTH_ZERO_SELF_SIGNED_CERT",
        "SELF_SIGNED_CERT_IN_CHAIN",
        "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
        "UNABLE_TO_GET_ISSUER_CERT",
        "UNABLE_TO_GET_ISSUER_CERT_LOCALLY",
        "CERT_HAS_EXPIRED",
        "CERT_NOT_YET_VALID",
        "CERT_UNTRUSTED",
        "CERT_REJECTED",
        "INVALID_CA",
        "INVALID_PURPOSE",
        "PATH_LENGTH_EXCEEDED",
    )
