package com.ditchoom.socket.testsuite.harness

/**
 * A resolved harness scenario endpoint — where a consumer test should connect.
 *
 * Values come from the harness controller's `GET /describe` manifest (never from
 * reading `harness.env` directly), so a consumer's `commonTest` code stays free of
 * any knowledge about how the docker-compose stack was launched or which ports it
 * pinned.
 */
data class HarnessEndpoint(
    val host: String,
    val port: Int,
)

/**
 * The TLS certificate-matrix scenarios exposed by the harness `tls` service
 * (one nginx vhost per scenario; see `test-harness/tls/`).
 *
 * [manifestKey] is the scenario name in the `/describe` manifest.
 */
enum class TlsScenario(
    val manifestKey: String,
) {
    /** Cert signed by the harness root CA, SAN matches the connect host. */
    VALID("tls-valid"),

    /** Self-signed leaf — untrusted by default stores. */
    SELF_SIGNED("tls-self-signed"),

    /** Backdated (expired) cert. */
    EXPIRED("tls-expired"),

    /** Valid chain but the SAN doesn't match the connect host. */
    WRONG_HOST("tls-wrong-host"),

    /** Signed by a CA the client doesn't trust. */
    UNTRUSTED("tls-untrusted"),

    /** Same cert as [VALID] but the vhost negotiates TLS 1.3 only (no 1.2 fallback). */
    TLS13_ONLY("tls13-only"),
}

/**
 * Host-visible toxiproxy ports from the `/describe` manifest: the control API plus
 * the three pre-published proxy listen ports (one per upstream). [host] is where the
 * consumer reaches them — the same host the controller itself was reached on.
 */
data class ToxiproxyPorts(
    val host: String,
    val api: Int,
    val echo: Int,
    val http: Int,
    val tls: Int,
)

/**
 * Host-visible `udp-toxi` relay ports from the `/describe` manifest (RFC_UNIFIED_NETWORK_TEST_HARNESS.md
 * §5): the HTTP control-plane API port ([api], TCP) and the pre-published suite relay's data-plane
 * listen port ([data], UDP). [host] is the host the controller itself was reached on. The datagram
 * analogue of [ToxiproxyPorts] — one relay is pre-pinned for `:socket-testsuite`'s [impairedUdp], name-
 * and port-isolated from any relay a parallel test-task family provisions.
 */
data class UdpToxiPorts(
    val host: String,
    val api: Int,
    val data: Int,
)
