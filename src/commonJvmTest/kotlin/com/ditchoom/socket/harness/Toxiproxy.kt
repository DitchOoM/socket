package com.ditchoom.socket.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

// ─────────────────────────────────────────────────────────────────────────────
// Toxiproxy control-API client for Phase-3 fault-injection tests.
//
// Lives in commonJvmTest so it's visible to both jvmTest and androidUnitTest.
// Wraps the toxiproxy 2.x HTTP/JSON API with hand-rolled HttpURLConnection
// calls and string-concatenated JSON payloads — no extra dependency.
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
     * already exists returns 409 Conflict, which we treat as success.
     *
     * Throws IllegalStateException if the toxiproxy API is unreachable.
     */
    fun ensureDefaultProxies() {
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
     * TCP probe of the API port; mirrors isHarnessAvailable() semantics but
     * for toxiproxy. Returns false on any failure, including the harness
     * being completely down.
     */
    suspend fun isToxiproxyAvailable(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    val timeoutMs = 500
                    s.connect(
                        InetSocketAddress(HarnessConfig.host, HarnessConfig.toxiproxyApiPort),
                        timeoutMs,
                    )
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

    /** Reset all toxics on a proxy (does NOT delete the proxy itself). */
    fun clearToxics(proxy: String) {
        // GET /proxies/<name>/toxics → array of {name,...}; DELETE each by name.
        val body = httpRequest("GET", "/proxies/$proxy/toxics", body = null, acceptConflict = false)
        // Naïvely extract toxic names — the response is a JSON array of objects
        // and each has a "name" field. Avoid pulling in a JSON parser.
        for (toxicName in extractJsonStringField(body, "name")) {
            httpRequest("DELETE", "/proxies/$proxy/toxics/$toxicName", body = null, acceptConflict = false)
        }
    }

    /**
     * Add a "down" toxic — proxy refuses connections / hangs up existing ones.
     * Use for SocketClosedException-from-EOF scenarios.
     */
    fun addDownToxic(
        proxy: String,
        name: String = "down",
    ) {
        // "down" is technically not a toxic in toxiproxy 2.x — disabling the
        // proxy itself drops connections. Express this by toggling enabled=false
        // on the proxy. We expose it under the "toxic" naming so tests read
        // consistently with the other helpers.
        httpRequest(
            method = "POST",
            path = "/proxies/$proxy",
            body = """{"enabled":false}""",
            acceptConflict = false,
        )
        // The `name` parameter is unused for the disable-proxy case; kept on the
        // signature so the call-site shape matches the other addXxxToxic helpers.
        @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION")
        name
    }

    /**
     * Add a "reset_peer" toxic — proxy sends RST after [timeoutMs] ms.
     * Use for ConnectionReset / EPIPE scenarios.
     */
    fun addResetPeerToxic(
        proxy: String,
        timeoutMs: Long = 0,
        name: String = "reset_peer",
    ) {
        val payload =
            """{"name":"${escapeJson(name)}","type":"reset_peer","stream":"downstream",""" +
                """"attributes":{"timeout":$timeoutMs}}"""
        httpRequest(
            method = "POST",
            path = "/proxies/$proxy/toxics",
            body = payload,
            acceptConflict = true,
        )
    }

    /**
     * Add a "slicer" toxic — splits writes into [averageSize]-byte pieces with
     * [delayMicros] microseconds between them. Use for partial-read assertions.
     */
    fun addSlicerToxic(
        proxy: String,
        averageSize: Int = 1,
        delayMicros: Long = 1000,
        name: String = "slicer",
    ) {
        val payload =
            """{"name":"${escapeJson(name)}","type":"slicer","stream":"downstream",""" +
                """"attributes":{"average_size":$averageSize,"size_variation":0,"delay":$delayMicros}}"""
        httpRequest(
            method = "POST",
            path = "/proxies/$proxy/toxics",
            body = payload,
            acceptConflict = true,
        )
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun upsertProxy(
        name: String,
        listen: String,
        upstream: String,
    ) {
        val payload =
            """{"name":"${escapeJson(name)}","listen":"${escapeJson(listen)}",""" +
                """"upstream":"${escapeJson(upstream)}","enabled":true}"""
        httpRequest(
            method = "POST",
            path = "/proxies",
            body = payload,
            acceptConflict = true,
        )
        // If the proxy already existed it may be in `enabled:false` from a
        // previous test class' addDownToxic — flip it back on so this run starts
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
        } catch (_: IOException) {
            // best-effort; if the proxy was just created there are none to clear.
        }
    }

    private fun httpRequest(
        method: String,
        path: String,
        body: String?,
        acceptConflict: Boolean,
    ): String {
        val url = URI("$baseUrl$path").toURL()
        val conn =
            try {
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 2_000
                    readTimeout = 5_000
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
            } catch (e: IOException) {
                throw IllegalStateException("toxiproxy API unreachable at $baseUrl$path", e)
            }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 409 && acceptConflict) {
                // already-exists / no-op on idempotent endpoints
                return conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw IllegalStateException("toxiproxy $method $path failed: HTTP $code — $err")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw IllegalStateException("toxiproxy $method $path I/O error", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extract all values of a top-level "key":"value" pair from a JSON blob.
     * Lightweight scan — sufficient for toxiproxy responses (no nested strings
     * named "name" appear within toxic objects in 2.12.0). Avoids dragging in
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
            // skip whitespace + colon + whitespace
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

    private fun escapeJson(s: String): String =
        buildString(s.length + 2) {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
}
