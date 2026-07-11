package com.ditchoom.socket.testsuite.controller

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.time.Duration.Companion.seconds

/**
 * W6 harness control-plane controller (RFC_DETERMINISTIC_SIMULATION §7).
 *
 * A tiny hand-rolled HTTP/1.1 server over the library's own [ServerSocket] (the
 * server-side mirror of the root module's `Toxiproxy.kt` client technique — no
 * third-party HTTP dependency, no JSON library; the manifest is pure ASCII so
 * `Content-Length` in characters equals bytes). Runs as the `controller` service in
 * `test-harness/docker-compose.yml`; consumers reach it through
 * `withNetworkHarness` in `com.ditchoom.socket.testsuite.harness`.
 *
 * Endpoints:
 *  - `GET /health`   → `200 ok` (compose healthcheck + cheap availability probe)
 *  - `GET /describe` → `200` JSON scenario manifest (see [HarnessManifest][com.ditchoom.socket.testsuite.harness.HarnessManifest])
 *  - `OPTIONS *`     → `204` (CORS preflight)
 *
 * Every response carries `Access-Control-Allow-Origin: *` so future browser targets
 * can fetch the manifest cross-origin.
 *
 * Configuration comes from the environment (compose passes `harness.env` via
 * `env_file`, mirroring the values the rest of the stack pins); defaults match
 * `test-harness/harness.env` so a bare `java -jar` run also works.
 */
object HarnessController {
    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key)?.trim().takeUnless { it.isNullOrEmpty() } ?: default

    /** Manifest version — bump when the wire shape changes incompatibly. */
    private const val MANIFEST_VERSION = 1

    /**
     * Build the `/describe` manifest from the environment. Endpoint hosts/ports are
     * the *host-visible* values (the consumer's perspective), exactly as pinned in
     * `harness.env` — the controller never rewrites them.
     */
    internal fun manifestJson(): String {
        val host = env("HARNESS_HOST", "127.0.0.1")

        fun ep(
            portKey: String,
            defaultPort: Int,
            epHost: String = host,
        ): String = """{"host":"$epHost","port":${env(portKey, defaultPort.toString())}}"""

        return buildString {
            append("""{"version":$MANIFEST_VERSION,"scenarios":{""")
            append(""""echo":${ep("ECHO_PORT", 14000)},""")
            append(""""http":${ep("HTTP_PORT", 14080)},""")
            append(""""tls-valid":${ep("TLS_VALID_PORT", 14443)},""")
            append(""""tls-self-signed":${ep("TLS_SELF_SIGNED_PORT", 14453)},""")
            append(""""tls-expired":${ep("TLS_EXPIRED_PORT", 14463)},""")
            append(""""tls-wrong-host":${ep("TLS_WRONG_HOST_PORT", 14473)},""")
            append(""""tls-untrusted":${ep("TLS_UNTRUSTED_PORT", 14483)},""")
            append(""""tls13-only":${ep("TLS_TLS13_PORT", 14493)},""")
            append(
                // `echo` is the SUITE port (15900), not the root module's 15000: the
                // manifest feeds NetworkHarness's `suite-echo` proxy, which must stay
                // isolated from the root tests' proxy table (parallel test tasks).
                """"toxiproxy":{"api":${env("TOXIPROXY_API_PORT", "8474")},""" +
                    """"echo":${env("TOXIPROXY_SUITE_ECHO_PORT", "15900")},""" +
                    """"http":${env("TOXIPROXY_HTTP_PORT", "15080")},""" +
                    """"tls":${env("TOXIPROXY_TLS_PORT", "15443")}},""",
            )
            append(""""rst":${ep("RST_PORT", 14998)},""")
            append(""""blackhole":${ep("NETEM_BLACKHOLE_PORT", 14999, env("NETEM_BLACKHOLE_HOST", "172.30.0.99"))},""")
            append(""""quic-echo":${ep("QUIC_ECHO_PORT", 14433)}""")
            append("}}")
        }
    }

    /**
     * Accept-and-serve loop. Each connection is handled in its own coroutine under a
     * supervisor so one bad client can never take down the accept loop.
     */
    suspend fun serve(
        bindHost: String,
        port: Int,
    ) {
        val manifest = manifestJson()
        val server = ServerSocket.allocate()
        val clients = server.bind(port = port, host = bindHost)
        println("[HarnessController] listening on $bindHost:${server.port()}")
        supervisorScope {
            clients.collect { client ->
                launch {
                    try {
                        handle(client, manifest)
                    } catch (_: Throwable) {
                        // Per-connection failure (client hangup, malformed request,
                        // read timeout) is not the controller's problem.
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    private suspend fun handle(
        socket: ClientSocket,
        manifest: String,
    ) {
        val head = readRequestHead(socket) ?: return
        // Request line: "METHOD /path HTTP/1.1"
        val line = head.substringBefore("\r\n")
        val parts = line.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return
        val response =
            when {
                method == "OPTIONS" -> preflightResponse()
                method == "GET" && path == "/health" -> response("200 OK", "text/plain", "ok")
                method == "GET" && path == "/describe" -> response("200 OK", "application/json", manifest)
                else -> response("404 Not Found", "text/plain", "not found")
            }
        socket.writeString(response, Charset.UTF8, 5.seconds)
    }

    /**
     * Read until the CRLF-CRLF end of the request head (the endpoints are all
     * body-less, so the head is the whole request). Returns null on a client that
     * disconnects or floods before finishing its head.
     */
    private suspend fun readRequestHead(socket: ClientSocket): String? {
        val head = StringBuilder()
        while (!head.endsWithHeaderBoundary()) {
            if (head.length > MAX_HEAD_CHARS) return null
            val chunk =
                try {
                    socket.readBuffer(5.seconds)
                } catch (_: Throwable) {
                    return null // EOF / reset / timeout before a complete head
                }
            repeat(chunk.remaining()) {
                head.append(chunk.readByte().toInt().toChar())
            }
        }
        return head.toString()
    }

    private fun StringBuilder.endsWithHeaderBoundary(): Boolean = indexOf("\r\n\r\n") >= 0

    private fun response(
        status: String,
        contentType: String,
        body: String,
    ): String =
        buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            // ASCII body — character count == byte count.
            append("Content-Length: ${body.length}\r\n")
            append(CORS_HEADERS)
            append("Connection: close\r\n")
            append("\r\n")
            append(body)
        }

    /** CORS preflight: 204, no body (and per RFC 9110 no Content-Length). */
    private fun preflightResponse(): String =
        buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append(CORS_HEADERS)
            append("Connection: close\r\n")
            append("\r\n")
        }

    private const val CORS_HEADERS =
        "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n"

    private const val MAX_HEAD_CHARS = 16384
}

fun main() {
    val bindHost = System.getenv("CONTROLLER_BIND")?.trim().takeUnless { it.isNullOrEmpty() } ?: "0.0.0.0"
    val port = System.getenv("CONTROLLER_PORT")?.trim()?.toIntOrNull() ?: 14100
    runBlocking {
        HarnessController.serve(bindHost, port)
    }
}
