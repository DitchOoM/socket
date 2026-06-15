package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.harnessHost
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// ─────────────────────────────────────────────────────────────────────────────
// Toxiproxy control-API client for Phase-3 fault-injection tests.
//
// Lives in `commonTest` so every platform with FULL_SOCKET_ACCESS (JVM, linuxX64,
// jsNode, Apple) runs the same conformance assertions against the same toxics.
// Implements the toxiproxy 2.x HTTP/JSON API on top of the library's own
// multiplatform `ClientSocket` — no JVM-only HttpURLConnection, no third-party
// HTTP client, no JSON parser. The JSON payloads we send are pure ASCII so
// Content-Length in characters equals Content-Length in bytes.
//
// Three proxies sit between tests and harness upstreams:
//
//    test ──► toxiproxy:15000 ──► echo:14000     (TOXIPROXY_ECHO_PORT)
//    test ──► toxiproxy:15080 ──► http:80        (TOXIPROXY_HTTP_PORT)
//    test ──► toxiproxy:15443 ──► tls:443        (TOXIPROXY_TLS_PORT)
//
// The upstream addresses use docker-compose service names (`echo:14000`,
// `http:80`, `tls:443`) which only resolve from inside the docker network.
// That's correct — toxiproxy IS inside the docker network; the test process
// reaches the proxies via the host-mapped listen ports.
// ─────────────────────────────────────────────────────────────────────────────
internal object Toxiproxy {
    /** Base URL of the toxiproxy control API on the host. */
    val baseUrl: String = "http://${HarnessConfig.host}:${HarnessConfig.toxiproxyApiPort}"

    /** Names of the three default proxies created by [ensureDefaultProxies]. */
    object Proxy {
        const val ECHO: String = "echo"
        const val HTTP: String = "http"
        const val TLS: String = "tls"
    }

    /**
     * Ensure the three default proxies exist and are listening, pointing at
     * the corresponding upstream services. Idempotent — POSTing a proxy that
     * already exists returns 409 Conflict, which we treat as success. After
     * upsert each proxy is re-enabled (in case a previous run left it disabled
     * via [addDownToxic]) and any leftover toxics are cleared.
     *
     * Throws [IllegalStateException] if the toxiproxy API is unreachable.
     */
    suspend fun ensureDefaultProxies() {
        upsertProxy(
            name = Proxy.ECHO,
            listen = "0.0.0.0:${HarnessConfig.toxiproxyEchoPort}",
            upstream = "echo:14000",
        )
        upsertProxy(
            name = Proxy.HTTP,
            listen = "0.0.0.0:${HarnessConfig.toxiproxyHttpPort}",
            upstream = "http:80",
        )
        upsertProxy(
            name = Proxy.TLS,
            listen = "0.0.0.0:${HarnessConfig.toxiproxyTlsPort}",
            upstream = "tls:443",
        )
    }

    /**
     * TCP probe of the API port — mirrors [com.ditchoom.socket.isHarnessAvailable]
     * but for toxiproxy. Returns `false` on any failure (including the harness
     * being completely down), so callers can early-return in test bodies.
     */
    suspend fun isToxiproxyAvailable(): Boolean =
        try {
            ClientSocket.connect(
                port = HarnessConfig.toxiproxyApiPort,
                hostname = harnessHost(),
                config = TransportConfig(connectTimeout = 500.milliseconds),
            ) { /* immediate close — we just needed to know the API is alive */ }
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Wipe all toxics on [proxy]. toxiproxy 2.x has no bulk-delete endpoint
     * (`DELETE /proxies/<name>/toxics` → 405 Method Not Allowed); instead we
     * GET the toxic list, scrape every `"name"` field, and DELETE each by name.
     * The JSON scan is hand-rolled to avoid pulling a JSON parser into the
     * test classpath — toxiproxy responses are flat enough for a single-pass
     * scan to be reliable (no nested objects share the "name" key in 2.12).
     */
    suspend fun clearToxics(proxy: String) {
        val body = httpGet("/proxies/$proxy/toxics")
        for (toxicName in extractJsonStringField(body, "name")) {
            httpRequest(
                method = "DELETE",
                path = "/proxies/$proxy/toxics/$toxicName",
                body = null,
                acceptConflict = false,
            )
        }
    }

    /**
     * Disable [proxy] entirely — toxiproxy drops existing connections and
     * refuses new ones. Use for SocketClosedException-from-EOF scenarios.
     *
     * `down` is not a real toxic type in toxiproxy 2.x; disabling the proxy is
     * the documented replacement. We keep the `addDownToxic` name so call-sites
     * read consistently with the other helpers.
     */
    @Suppress("UNUSED_PARAMETER") // `name` kept for shape parity with the other helpers.
    suspend fun addDownToxic(
        proxy: String,
        name: String = "down",
    ) {
        httpRequest(
            method = "POST",
            path = "/proxies/$proxy",
            body = """{"enabled":false}""",
            acceptConflict = false,
        )
    }

    /**
     * Add a `reset_peer` toxic — proxy sends RST after [timeoutMs] ms. With
     * `timeoutMs = 0` the very next packet through the proxy draws an immediate
     * RST. Use for ConnectionReset scenarios.
     */
    suspend fun addResetPeerToxic(
        proxy: String,
        timeoutMs: Long = 0,
        name: String = "reset_peer",
    ) {
        val payload =
            """{"name":"$name","type":"reset_peer","stream":"downstream",""" +
                """"attributes":{"timeout":$timeoutMs}}"""
        httpRequest(
            method = "POST",
            path = "/proxies/$proxy/toxics",
            body = payload,
            acceptConflict = true,
        )
    }

    /**
     * Add a `slicer` toxic — splits writes into [averageSize]-byte pieces with
     * [delayMicros] microseconds between them. With `averageSize = 1` this
     * forces per-byte delivery, which is what the partial-read UTF-8 test needs.
     */
    suspend fun addSlicerToxic(
        proxy: String,
        averageSize: Int = 1,
        delayMicros: Long = 1000,
        name: String = "slicer",
    ) {
        val payload =
            """{"name":"$name","type":"slicer","stream":"downstream",""" +
                """"attributes":{"average_size":$averageSize,"size_variation":0,"delay":$delayMicros}}"""
        httpRequest(
            method = "POST",
            path = "/proxies/$proxy/toxics",
            body = payload,
            acceptConflict = true,
        )
    }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun upsertProxy(
        name: String,
        listen: String,
        upstream: String,
    ) {
        val payload =
            """{"name":"$name","listen":"$listen","upstream":"$upstream","enabled":true}"""
        // 409 Conflict on POST /proxies = "already exists" — accept it.
        httpRequest(
            method = "POST",
            path = "/proxies",
            body = payload,
            acceptConflict = true,
        )
        // If the proxy already existed it may be in `enabled:false` from a
        // previous test's addDownToxic — flip it back on so this run starts
        // from a clean baseline.
        httpRequest(
            method = "POST",
            path = "/proxies/$name",
            body = """{"enabled":true}""",
            acceptConflict = false,
        )
        // Also wipe any toxics left over from a previous test.
        try {
            clearToxics(name)
        } catch (_: Throwable) {
            // best-effort; if the proxy was just created there are none to clear.
        }
    }

    /**
     * GET shortcut returning the response body. Used by [clearToxics] which
     * has to scrape the toxic list to delete each entry by name (toxiproxy
     * 2.x doesn't support bulk-delete on `/proxies/<name>/toxics`).
     */
    private suspend fun httpGet(path: String): String {
        val (code, body) = httpExchange("GET", path, requestBody = null)
        if (code !in 200..299) {
            throw IllegalStateException("toxiproxy GET $path failed: HTTP $code")
        }
        return body
    }

    /**
     * Send a single HTTP request to the toxiproxy control API over a fresh
     * `ClientSocket`. Caller can opt-in to treat a 409 Conflict as success
     * (used by idempotent create endpoints).
     *
     * @throws IllegalStateException on any I/O failure or non-success status.
     */
    private suspend fun httpRequest(
        method: String,
        path: String,
        body: String?,
        acceptConflict: Boolean,
    ) {
        val (code, _) = httpExchange(method, path, requestBody = body)
        if (code == 409 && acceptConflict) return // already-exists / no-op
        if (code !in 200..299) {
            throw IllegalStateException("toxiproxy $method $path failed: HTTP $code")
        }
    }

    /**
     * The single I/O primitive. Why hand-roll the HTTP wire format on top of
     * `ClientSocket`? The library targets multi-platform and we cannot depend
     * on a Kotlin HTTP client in the test source set — but `ClientSocket` is
     * the library's own surface, already on the test classpath, multiplatform.
     *
     * Drains the full response, splits on the CRLF-CRLF header boundary, and
     * returns `(status, body)`. We send `Connection: close` and rely on the
     * peer FIN to terminate the read loop, which is more robust than parsing
     * Content-Length / Transfer-Encoding from toxiproxy's responses (it
     * sometimes returns chunked, sometimes content-length).
     *
     * @throws IllegalStateException on any I/O failure.
     */
    private suspend fun httpExchange(
        method: String,
        path: String,
        requestBody: String?,
    ): Pair<Int, String> {
        val host = harnessHost()
        // ASCII-only JSON payloads — character length == byte length.
        // (toxiproxy parses Content-Length strictly.)
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
                port = HarnessConfig.toxiproxyApiPort,
                hostname = host,
                config = TransportConfig(connectTimeout = 5.seconds),
            ) { socket ->
                socket.writeString(request, Charset.UTF8, 5.seconds)
                // Drain the response until the peer closes. `Connection: close`
                // on the request guarantees toxiproxy FINs the socket after the
                // body — so `read()` returns SocketClosedException.EndOfStream
                // (or platform equivalent) which we treat as "done draining".
                val full = StringBuilder()
                try {
                    while (true) {
                        val chunk = socket.readBuffer(5.seconds)
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
            throw IllegalStateException("toxiproxy API unreachable at $baseUrl$path", e)
        }
    }

    /**
     * Parse a raw HTTP response into `(statusCode, body)`. Handles both
     * `Content-Length` and `Transfer-Encoding: chunked` responses by simply
     * returning everything past the header CRLF-CRLF — toxiproxy callers only
     * need the body to scrape JSON `"name"` fields, which the chunked-encoding
     * size markers don't disturb (they're hex on their own lines, never a
     * `"name":"…"` substring).
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
            throw IllegalStateException("toxiproxy $method $path: malformed status line in '${raw.take(40)}'")
        }
        val code = raw.substring(firstSpace + 1, secondSpace).toInt()
        val headerEnd = raw.indexOf("\r\n\r\n")
        val body = if (headerEnd >= 0) raw.substring(headerEnd + 4) else ""
        return code to body
    }

    /**
     * Extract all values of a top-level `"key":"value"` pair from a JSON blob.
     * Lightweight scan — sufficient for toxiproxy 2.12 responses (no nested
     * strings named "name" appear within toxic objects). Avoids dragging in
     * a JSON dependency for one helper call.
     */
    private fun extractJsonStringField(
        json: String,
        key: String,
    ): List<String> {
        val needle = "\"$key\""
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val k = json.indexOf(needle, i)
            if (k < 0) break
            var j = k + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != ':') {
                i = k + needle.length
                continue
            }
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != '"') {
                i = k + needle.length
                continue
            }
            j++
            val sb = StringBuilder()
            while (j < json.length && json[j] != '"') {
                if (json[j] == '\\' && j + 1 < json.length) {
                    sb.append(json[j + 1])
                    j += 2
                } else {
                    sb.append(json[j])
                    j++
                }
            }
            out += sb.toString()
            i = j + 1
        }
        return out
    }
}
