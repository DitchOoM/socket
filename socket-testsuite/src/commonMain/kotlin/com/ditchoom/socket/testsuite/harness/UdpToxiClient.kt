package com.ditchoom.socket.testsuite.harness

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.testkit.fault.FaultScheduleCodec
import kotlin.time.Duration.Companion.milliseconds

/**
 * Control-plane client for the `udp-toxi` relay sidecar (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §5, §11.1)
 * — the UDP/QUIC analogue of [ToxiproxyClient]. It provisions a named relay and pushes a
 * [FaultSchedule] over a plain HTTP/1.1 REST **control plane on TCP**, exactly the two-plane split the
 * RFC settled on: the control API is *about* UDP traffic, it does not run *over* it. The relay's
 * **data plane** — the datagrams it impairs and forwards — is UDP, invisible to this client.
 *
 * A relay is toxiproxy-shaped: named, bound to its own data-plane UDP `listen` port, forwarding to a
 * fixed `upstream`. The name+port pair is what isolates one Gradle test-task family's relay from
 * another's (the `suite-echo`@15900 lesson, applied to datagrams).
 *
 * The pushed schedule is serialized with the shared [FaultScheduleCodec], so the relay drives the very
 * same [com.ditchoom.socket.testkit.fault.ImpairmentEngine] the in-process Tier-A pipe does — that
 * shared interpretation is the Tier-A ⇄ Tier-C parity guarantee.
 */
class UdpToxiClient(
    private val host: String,
    private val apiPort: Int,
) {
    /** TCP probe of the control-API port (500 ms budget). `false` on any failure so callers skip, not fail. */
    suspend fun isAvailable(): Boolean =
        try {
            ClientSocket.connect(
                port = apiPort,
                hostname = host,
                config = TransportConfig(connectTimeout = 500.milliseconds),
            ) { /* immediate close — liveness probe only */ }
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Create-or-reset a relay: bind (if needed) a data-plane UDP socket on [listenPort] forwarding to
     * [upstream] (an address resolvable *inside* the harness network, e.g. `"udp-echo:14434"`), and
     * clear both directions back to [FaultSchedule.CLEAN]. Idempotent — re-provisioning an existing
     * relay just resets its schedules.
     */
    suspend fun upsertRelay(
        name: String,
        listenPort: Int,
        upstream: String,
    ) {
        val payload = """{"name":"$name","listen":$listenPort,"upstream":"$upstream"}"""
        request(method = "POST", path = "/relays", body = payload, acceptConflict = true)
    }

    /**
     * Install [schedule] on the [direction] leg of [relay]. The body is the [FaultScheduleCodec]
     * document — the same text a fixture carries.
     */
    suspend fun setSchedule(
        relay: String,
        direction: RelayDirection,
        schedule: FaultSchedule,
    ) {
        request(
            method = "POST",
            path = "/relays/$relay/schedule?direction=${direction.wire}",
            body = FaultScheduleCodec.encode(schedule),
            acceptConflict = false,
        )
    }

    /** Clear both directions of [relay] back to [FaultSchedule.CLEAN] (the scenario-teardown call). */
    suspend fun clearSchedules(relay: String) {
        request(method = "DELETE", path = "/relays/$relay/schedule", body = null, acceptConflict = false)
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
            throw IllegalStateException("udp-toxi $method $path failed: HTTP $code")
        }
    }
}

/**
 * Which leg of a relay a [FaultSchedule] impairs — datagrams flowing from the test client toward the
 * upstream echo, or the replies coming back. A bare exhaustive enum (no payload): the two directions are
 * impaired by independent engines, mirroring [com.ditchoom.socket.udp] Tier-A's two-schedule pipe.
 */
enum class RelayDirection(
    val wire: String,
) {
    ClientToServer("clientToServer"),
    ServerToClient("serverToClient"),
}
