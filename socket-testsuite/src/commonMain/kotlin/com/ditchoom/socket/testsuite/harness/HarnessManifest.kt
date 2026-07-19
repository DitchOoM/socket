package com.ditchoom.socket.testsuite.harness

/**
 * Parsed `GET /describe` manifest from the harness controller.
 *
 * Wire shape (version 1 — all values sourced from `test-harness/harness.env` via the
 * controller's environment):
 *
 * ```json
 * {
 *   "version": 1,
 *   "scenarios": {
 *     "echo":            {"host":"127.0.0.1","port":14000},
 *     "http":            {"host":"127.0.0.1","port":14080},
 *     "tls-valid":       {"host":"127.0.0.1","port":14443},
 *     "tls-self-signed": {"host":"127.0.0.1","port":14453},
 *     "tls-expired":     {"host":"127.0.0.1","port":14463},
 *     "tls-wrong-host":  {"host":"127.0.0.1","port":14473},
 *     "tls-untrusted":   {"host":"127.0.0.1","port":14483},
 *     "tls13-only":      {"host":"127.0.0.1","port":14493},
 *     "toxiproxy":       {"api":8474,"echo":15900,"http":15080,"tls":15443},
 *     "rst":             {"host":"127.0.0.1","port":14998},
 *     "blackhole":       {"host":"172.30.0.99","port":14999},
 *     "quic-echo":       {"host":"127.0.0.1","port":14433},
 *     "udp-echo":        {"host":"127.0.0.1","port":14434},
 *     "udp-toxi":        {"api":8475,"data":14435}
 *   }
 * }
 * ```
 *
 * A scenario absent from the manifest is unavailable on this harness runtime
 * (RFC_DETERMINISTIC_SIMULATION §7) — [scenario] throws a descriptive error for
 * those; use [scenarioOrNull] to probe. Unknown scenario names from a newer
 * controller are ignored (forward-compatible).
 */
class HarnessManifest internal constructor(
    val version: Int,
    val scenarios: Map<String, HarnessEndpoint>,
    val toxiproxy: ToxiproxyPorts?,
    val udpToxi: UdpToxiPorts?,
) {
    fun scenarioOrNull(name: String): HarnessEndpoint? = scenarios[name]

    fun scenario(name: String): HarnessEndpoint =
        scenarios[name]
            ?: throw IllegalStateException(
                "harness scenario '$name' is not in the /describe manifest " +
                    "(available: ${scenarios.keys.sorted()}) — unavailable on this harness runtime",
            )

    companion object {
        /** Scenario names carrying a plain `{host,port}` endpoint in manifest v1. */
        private val ENDPOINT_SCENARIOS =
            listOf(
                "echo",
                "http",
                "tls-valid",
                "tls-self-signed",
                "tls-expired",
                "tls-wrong-host",
                "tls-untrusted",
                "tls13-only",
                "rst",
                "blackhole",
                "quic-echo",
                "udp-echo",
            )

        /**
         * Parse a manifest body. [defaultHost] (the host the controller was reached
         * on) fills in any endpoint that omits `"host"` — notably the `toxiproxy`
         * entry, which carries only ports.
         */
        internal fun parse(
            json: String,
            defaultHost: String,
        ): HarnessManifest {
            val version = HarnessJson.intField(json, "version") ?: 1
            val scenariosJson =
                HarnessJson.objectField(json, "scenarios")
                    ?: throw IllegalStateException("harness manifest has no \"scenarios\" object: '${json.take(80)}'")
            val scenarios = mutableMapOf<String, HarnessEndpoint>()
            for (name in ENDPOINT_SCENARIOS) {
                val obj = HarnessJson.objectField(scenariosJson, name) ?: continue
                val port = HarnessJson.intField(obj, "port") ?: continue
                scenarios[name] = HarnessEndpoint(HarnessJson.stringField(obj, "host") ?: defaultHost, port)
            }
            val toxiproxy =
                HarnessJson.objectField(scenariosJson, "toxiproxy")?.let { obj ->
                    val api = HarnessJson.intField(obj, "api")
                    val echo = HarnessJson.intField(obj, "echo")
                    val http = HarnessJson.intField(obj, "http")
                    val tls = HarnessJson.intField(obj, "tls")
                    if (api != null && echo != null && http != null && tls != null) {
                        ToxiproxyPorts(
                            host = HarnessJson.stringField(obj, "host") ?: defaultHost,
                            api = api,
                            echo = echo,
                            http = http,
                            tls = tls,
                        )
                    } else {
                        null
                    }
                }
            val udpToxi =
                HarnessJson.objectField(scenariosJson, "udp-toxi")?.let { obj ->
                    val api = HarnessJson.intField(obj, "api")
                    val data = HarnessJson.intField(obj, "data")
                    if (api != null && data != null) {
                        UdpToxiPorts(host = HarnessJson.stringField(obj, "host") ?: defaultHost, api = api, data = data)
                    } else {
                        null
                    }
                }
            return HarnessManifest(version, scenarios, toxiproxy, udpToxi)
        }
    }
}
