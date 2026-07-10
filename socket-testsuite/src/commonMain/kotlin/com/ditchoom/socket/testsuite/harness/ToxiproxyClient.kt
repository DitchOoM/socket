package com.ditchoom.socket.testsuite.harness

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import kotlin.time.Duration.Companion.milliseconds

/**
 * Toxiproxy 2.x control-API client over the library's own [ClientSocket] —
 * the consumer-facing generalization of the root module's commonTest `Toxiproxy`
 * helper (which stays where it is: the root module can't depend on this module
 * without a project cycle).
 *
 * All calls are idempotent where the underlying API allows it: creating a proxy
 * that already exists (409 Conflict) is treated as success, and [upsertProxy]
 * re-enables the proxy + wipes leftover toxics so each scenario starts from a
 * clean baseline.
 */
class ToxiproxyClient(
    private val host: String,
    private val apiPort: Int,
) {
    /**
     * TCP probe of the control-API port with a 500 ms budget. Returns `false` on
     * any failure so callers can skip instead of flaky-failing.
     */
    suspend fun isAvailable(): Boolean =
        try {
            ClientSocket.connect(
                port = apiPort,
                hostname = host,
                config = TransportConfig(connectTimeout = 500.milliseconds),
            ) { /* immediate close — we just needed to know the API is alive */ }
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Create-or-reset a proxy: POST it (409 = already exists = fine), flip it back
     * to `enabled` (a previous scenario may have disabled it), and wipe any
     * leftover toxics.
     *
     * @param listen container-side listen address, e.g. `"0.0.0.0:15000"`.
     * @param upstream upstream address *as resolvable from inside the harness
     *   network*, e.g. the compose service address `"echo:14000"`.
     */
    suspend fun upsertProxy(
        name: String,
        listen: String,
        upstream: String,
    ) {
        val payload =
            """{"name":"$name","listen":"$listen","upstream":"$upstream","enabled":true}"""
        request(method = "POST", path = "/proxies", body = payload, acceptConflict = true)
        enableProxy(name)
        try {
            clearToxics(name)
        } catch (_: Throwable) {
            // best-effort; a freshly created proxy has none to clear.
        }
    }

    /** Re-enable [proxy] (undoes [disableProxy]). */
    suspend fun enableProxy(proxy: String) {
        request(
            method = "POST",
            path = "/proxies/$proxy",
            body = """{"enabled":true}""",
            acceptConflict = false,
        )
    }

    /**
     * Disable [proxy] entirely — toxiproxy drops existing connections and refuses
     * new ones. The toxiproxy-2.x replacement for the removed `down` toxic.
     */
    suspend fun disableProxy(proxy: String) {
        request(
            method = "POST",
            path = "/proxies/$proxy",
            body = """{"enabled":false}""",
            acceptConflict = false,
        )
    }

    /**
     * Wipe all toxics on [proxy]. toxiproxy 2.x has no bulk-delete endpoint, so we
     * GET the toxic list, scrape every `"name"`, and DELETE each one.
     */
    suspend fun clearToxics(proxy: String) {
        val body = get("/proxies/$proxy/toxics")
        for (toxicName in HarnessJson.stringValues(body, "name")) {
            request(
                method = "DELETE",
                path = "/proxies/$proxy/toxics/$toxicName",
                body = null,
                acceptConflict = false,
            )
        }
    }

    /**
     * Add a `latency` toxic: [latencyMs] added to every chunk flowing [stream]ward,
     * plus/minus up to [jitterMs].
     */
    suspend fun addLatencyToxic(
        proxy: String,
        latencyMs: Long,
        jitterMs: Long = 0,
        stream: String = "downstream",
        name: String = "latency",
    ) {
        val payload =
            """{"name":"$name","type":"latency","stream":"$stream",""" +
                """"attributes":{"latency":$latencyMs,"jitter":$jitterMs}}"""
        request(method = "POST", path = "/proxies/$proxy/toxics", body = payload, acceptConflict = true)
    }

    /**
     * Add a `reset_peer` toxic — the proxy sends RST after [timeoutMs] ms; with `0`
     * the very next packet through the proxy draws an immediate RST.
     */
    suspend fun addResetPeerToxic(
        proxy: String,
        timeoutMs: Long = 0,
        name: String = "reset_peer",
    ) {
        val payload =
            """{"name":"$name","type":"reset_peer","stream":"downstream",""" +
                """"attributes":{"timeout":$timeoutMs}}"""
        request(method = "POST", path = "/proxies/$proxy/toxics", body = payload, acceptConflict = true)
    }

    /**
     * Add a `slicer` toxic — splits writes into [averageSize]-byte pieces with
     * [delayMicros] µs between them (per-byte delivery with `averageSize = 1`).
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
        request(method = "POST", path = "/proxies/$proxy/toxics", body = payload, acceptConflict = true)
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private suspend fun get(path: String): String {
        val (code, body) = harnessHttpExchange(host, apiPort, "GET", path)
        if (code !in 200..299) {
            throw IllegalStateException("toxiproxy GET $path failed: HTTP $code")
        }
        return body
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        acceptConflict: Boolean,
    ) {
        val (code, _) = harnessHttpExchange(host, apiPort, method, path, requestBody = body)
        if (code == 409 && acceptConflict) return // already-exists / no-op
        if (code !in 200..299) {
            throw IllegalStateException("toxiproxy $method $path failed: HTTP $code")
        }
    }
}
