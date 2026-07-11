package com.ditchoom.socket.testsuite.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Minimal HTTP/1.1 exchange over the library's own multiplatform `ClientSocket` —
 * generalized from the proven root-module `Toxiproxy.kt` client (which stays in the
 * root's commonTest untouched: the root module cannot depend on :socket-testsuite
 * without a project cycle).
 *
 * Why hand-rolled: this lives in a MAIN source set of a consumer-facing test-support
 * module, so a third-party HTTP client would become a transitive dependency of every
 * consumer's test classpath. `ClientSocket` is already there, and the payloads are
 * tiny ASCII JSON (character length == byte length, so Content-Length is exact).
 *
 * Works on every :socket-testsuite target (JVM/Android/Linux/Apple) because it is
 * plain common code over `ClientSocket`. Browser targets are future work: the module
 * has no js/wasmJs target today, and a browser cannot open raw TCP — when a browser
 * target is added, this seam is where a `fetch`-based control transport plugs in
 * (the controller already sends CORS headers in anticipation).
 */

/**
 * Send a single HTTP/1.1 request and return `(statusCode, body)`.
 *
 * Sends `Connection: close` and drains until peer FIN, which sidesteps
 * Content-Length/chunked parsing on the response path (same trade-off as the root
 * module's Toxiproxy client — see that file's rationale).
 *
 * @throws IllegalStateException on any I/O failure (connect refused, timeout, …).
 */
internal suspend fun harnessHttpExchange(
    host: String,
    port: Int,
    method: String,
    path: String,
    requestBody: String? = null,
    connectTimeout: Duration = 5.seconds,
    ioTimeout: Duration = 5.seconds,
): Pair<Int, String> {
    // ASCII-only payloads — character length == byte length.
    val contentLength = requestBody?.length ?: 0
    val request =
        buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            if (requestBody != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: $contentLength\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
            if (requestBody != null) append(requestBody)
        }
    return try {
        ClientSocket.connect(
            port = port,
            hostname = host,
            config = TransportConfig(connectTimeout = connectTimeout),
        ) { socket ->
            socket.writeString(request, Charset.UTF8, ioTimeout)
            val full = StringBuilder()
            try {
                while (true) {
                    val chunk = socket.readBuffer(ioTimeout)
                    val remaining = chunk.remaining()
                    repeat(remaining) {
                        full.append(chunk.readByte().toInt().toChar())
                    }
                }
            } catch (_: SocketClosedException) {
                // peer FIN — full response received
            }
            parseHttpResponse(full.toString(), method, path)
        }
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Throwable) {
        throw IllegalStateException("harness HTTP $method http://$host:$port$path failed", e)
    }
}

/**
 * Parse a raw HTTP response into `(statusCode, body)` — everything past the
 * CRLF-CRLF header boundary is the body (chunked size markers, when present, never
 * collide with the flat JSON fields the callers scan for).
 */
private fun parseHttpResponse(
    raw: String,
    method: String,
    path: String,
): Pair<Int, String> {
    // Status line: "HTTP/1.1 NNN <reason>\r\n"
    val firstSpace = raw.indexOf(' ')
    val secondSpace = if (firstSpace >= 0) raw.indexOf(' ', firstSpace + 1) else -1
    if (firstSpace < 0 || secondSpace < 0) {
        throw IllegalStateException("harness HTTP $method $path: malformed status line in '${raw.take(40)}'")
    }
    val code =
        raw.substring(firstSpace + 1, secondSpace).toIntOrNull()
            ?: throw IllegalStateException("harness HTTP $method $path: non-numeric status in '${raw.take(40)}'")
    val headerEnd = raw.indexOf("\r\n\r\n")
    val body = if (headerEnd >= 0) raw.substring(headerEnd + 4) else ""
    return code to body
}
