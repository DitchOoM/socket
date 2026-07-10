package com.ditchoom.socket.testsuite.harness

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Default controller endpoint — `CONTROLLER_PORT` in `test-harness/harness.env`. */
const val DEFAULT_CONTROLLER_HOST: String = "127.0.0.1"
const val DEFAULT_CONTROLLER_PORT: Int = 14100

/**
 * Consumer entry point to the container harness control plane
 * (RFC_DETERMINISTIC_SIMULATION §7).
 *
 * Fetches the scenario manifest from the harness controller's `GET /describe`, then
 * runs [block] with a [NetworkHarnessScope] that resolves scenarios to typed
 * [HarnessEndpoint]s and provisions impairments through toxiproxy — plain
 * `commonTest` code, no docker CLI, no platform-specific test code:
 *
 * ```kotlin
 * withNetworkHarness {
 *     val echoed = roundTrip(echo(), "hello")           // plain TCP echo
 *     tls(TlsScenario.EXPIRED)                          // typed cert-matrix endpoint
 *     impaired(latency = 200.milliseconds) { proxy ->   // +200 ms via toxiproxy
 *         roundTrip(proxy, "slow")
 *     }
 * }
 * ```
 *
 * **Skip-on-unreachable, never flaky-fail** (the `isHarnessAvailable()` /
 * `QuicPublicEndpointInteropTests` idiom): when the controller can't be reached, or
 * returns something unparsable, [block] is skipped, a single loud line is printed,
 * and `false` is returned — the harness being down (no docker, Windows CI,
 * `HARNESS_DISABLED=true`) must never fail a consumer's suite. Exceptions thrown by
 * [block] itself (real assertion failures) always propagate.
 *
 * Runs on every :socket-testsuite target (JVM/Android/Linux/Apple) — the control
 * transport is the library's own [com.ditchoom.socket.ClientSocket]. Browser targets
 * are future work (no js/wasmJs target on this module today; the controller already
 * serves CORS headers so a `fetch`-based transport can slot in).
 *
 * @return `true` if the manifest resolved and [block] ran; `false` if skipped.
 */
suspend fun withNetworkHarness(
    controllerHost: String = DEFAULT_CONTROLLER_HOST,
    controllerPort: Int = DEFAULT_CONTROLLER_PORT,
    block: suspend NetworkHarnessScope.() -> Unit,
): Boolean {
    val manifest =
        try {
            val (code, body) =
                harnessHttpExchange(
                    host = controllerHost,
                    port = controllerPort,
                    method = "GET",
                    path = "/describe",
                    connectTimeout = 2.seconds,
                )
            if (code !in 200..299) {
                println(
                    "[withNetworkHarness] controller at http://$controllerHost:$controllerPort/describe " +
                        "returned HTTP $code — skipping harness scenarios",
                )
                return false
            }
            HarnessManifest.parse(body, defaultHost = controllerHost)
        } catch (t: Throwable) {
            println(
                "[withNetworkHarness] harness controller unreachable at " +
                    "http://$controllerHost:$controllerPort/describe — skipping harness scenarios " +
                    "(${t.message})",
            )
            return false
        }
    NetworkHarnessScope(controllerHost, manifest).block()
    return true
}

/**
 * Scoped accessors over a resolved [HarnessManifest]. Obtained via
 * [withNetworkHarness]; every endpoint comes from the controller's manifest, never
 * from build-time constants.
 */
class NetworkHarnessScope internal constructor(
    val controllerHost: String,
    val manifest: HarnessManifest,
) {
    /** Plain TCP echo (socat `EXEC:cat`). The cheapest end-to-end round-trip. */
    fun echo(): HarnessEndpoint = manifest.scenario("echo")

    /** HTTP/1.1 (nginx): `/get` → `ok`, `/json`, `/large`, CORS-permissive. */
    fun http(): HarnessEndpoint = manifest.scenario("http")

    /** TLS cert-matrix vhost for [scenario] (valid / expired / wrong-host / …). */
    fun tls(scenario: TlsScenario): HarnessEndpoint = manifest.scenario(scenario.manifestKey)

    /** QUIC echo server (UDP) — the harness replacement for public QUIC hosts. */
    fun quicEcho(): HarnessEndpoint = manifest.scenario("quic-echo")

    /**
     * Deterministic peer-close sidecar: after the client's first byte, the server
     * sets `SO_LINGER=0` and closes → RST. The test controls *when* by *when* it
     * writes (see the root module's `pendingReadDuringPeerReset` consumer).
     */
    fun rst(): HarnessEndpoint = manifest.scenario("rst")

    /**
     * The netem blackhole endpoint: SYN accepted at L3, every egress packet dropped,
     * so connects time out deterministically. No provisioning needed — exposed in
     * block form for API symmetry with [impaired]/[peerReset].
     */
    suspend fun blackhole(block: suspend (HarnessEndpoint) -> Unit) {
        block(manifest.scenario("blackhole"))
    }

    /**
     * Run [block] against a toxiproxy-fronted echo endpoint with [latency] (±
     * [jitter]) added to every downstream chunk. The proxy is (re)provisioned
     * through the toxiproxy control API and its toxics are cleared afterwards, so
     * scenarios are isolated.
     */
    suspend fun impaired(
        latency: Duration,
        jitter: Duration? = null,
        block: suspend (HarnessEndpoint) -> Unit,
    ) {
        val (client, tox) = provisionEchoProxy()
        client.addLatencyToxic(
            proxy = ECHO_PROXY,
            latencyMs = latency.inWholeMilliseconds,
            jitterMs = jitter?.inWholeMilliseconds ?: 0,
        )
        try {
            block(HarnessEndpoint(tox.host, tox.echo))
        } finally {
            runCatching { client.clearToxics(ECHO_PROXY) }
        }
    }

    /**
     * Run [block] against a toxiproxy-fronted echo endpoint that RSTs the connection
     * on the next packet through the proxy (`reset_peer`, timeout 0). For the
     * *deterministic single-byte-triggered* variant use the [rst] sidecar instead.
     */
    suspend fun peerReset(block: suspend (HarnessEndpoint) -> Unit) {
        val (client, tox) = provisionEchoProxy()
        client.addResetPeerToxic(proxy = ECHO_PROXY, timeoutMs = 0)
        try {
            block(HarnessEndpoint(tox.host, tox.echo))
        } finally {
            runCatching { client.clearToxics(ECHO_PROXY) }
        }
    }

    /**
     * Upsert the echo proxy (fresh, enabled, toxic-free) and return the control
     * client + ports. The upstream uses the compose service address ([ECHO_UPSTREAM])
     * — resolvable from *inside* the harness network, where toxiproxy lives.
     */
    private suspend fun provisionEchoProxy(): Pair<ToxiproxyClient, ToxiproxyPorts> {
        val tox =
            manifest.toxiproxy
                ?: throw IllegalStateException(
                    "harness manifest has no 'toxiproxy' scenario — impairments unavailable on this runtime",
                )
        val client = ToxiproxyClient(tox.host, tox.api)
        client.upsertProxy(name = ECHO_PROXY, listen = "0.0.0.0:${tox.echo}", upstream = ECHO_UPSTREAM)
        return client to tox
    }

    private companion object {
        /** Proxy name shared with the root module's Toxiproxy helper — same proxy table. */
        const val ECHO_PROXY = "echo"

        /** Compose-internal address of the echo upstream (toxiproxy resolves it in-network). */
        const val ECHO_UPSTREAM = "echo:14000"
    }
}
