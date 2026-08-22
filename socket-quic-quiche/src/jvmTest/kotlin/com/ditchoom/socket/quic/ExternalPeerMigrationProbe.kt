package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * SCRATCH — a hand-driven probe against a **real, third-party** QUIC deployment. Gated on
 * `QUIC_PROBE_HOST` so CI never reaches the public internet.
 *
 * ## What it settles
 * #445 is only reachable when an endpoint retires a source CID while packets bearing it are still in
 * flight — which, for a peer we do not build, means *we* actively migrated and sent
 * RETIRE_CONNECTION_ID. So the whole question of whether a third-party quiche deployment is exposed
 * reduces to: **does it permit active migration at all?**
 *
 * A peer that sets `disable_active_migration` (RFC 9000 §18.2) can never be put in that state by us.
 *
 * ## Why it prints the neighbouring transport parameters
 * `MigrationResult.Unmoved.PeerForbids` is also the exact symptom of #388 — quiche's `TransportParams`
 * lost its `#[repr(C)]` upstream, so `peer_disable_active_migration` sat at offset 96 while the header
 * declared 80, and every backend read `active_conn_id_limit` as the bool. Migration then "silently
 * refuses" against every peer. `sizeof` matches under both layouts, so only the neighbouring fields
 * can tell a real refusal from a misread struct — exactly as [PeerTransportParamsLayoutTestSuite]
 * asserts against a server whose parameters we chose. Here the values are the peer's, so they are
 * printed for a human to sanity-check rather than asserted: an `activeConnIdLimit` of -1, or an idle
 * timeout of 0, means the struct is being misread and the refusal is ours, not theirs.
 *
 * ```
 * QUIC_PROBE_HOST=cloudflare-quic.com ./gradlew :socket-quic-quiche:jvmTest \
 *   --tests "com.ditchoom.socket.quic.ExternalPeerMigrationProbe" --rerun
 * ```
 *
 * `verifyPeer` is off: this measures transport behaviour, not our certificate handling.
 */
class ExternalPeerMigrationProbe {
    private fun env(
        name: String,
        fallback: String,
    ): String = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fallback

    @Test
    fun reportWhetherTheExternalPeerPermitsActiveMigration() {
        val host = env("QUIC_PROBE_HOST", "")
        assumeTrue("hand-driven probe — set QUIC_PROBE_HOST (see class KDoc)", host.isNotEmpty())
        val port = env("QUIC_PROBE_PORT", "443").toInt()
        val alpn = env("QUIC_PROBE_ALPN", "h3")

        val options =
            QuicOptions(
                alpnProtocols = listOf(alpn),
                verifyPeer = false,
                idleTimeout = 20.seconds,
            )

        runBlocking {
            withQuicConnection(host, port, options, TransportConfig(), timeout = 30.seconds) {
                println("PROBE host=$host:$port alpn=$negotiatedAlpn session=${identity.session}")
                val driver = (this as QuicheBackedConnection).quicheDriver
                val params = driver.peerTransportParams()
                val negotiated = assertIs<PeerTransportParams.Negotiated>(params, "handshake completed but no peer params")
                println("PROBE peer.disableActiveMigration = ${negotiated.disableActiveMigration}")
                println(
                    "PROBE neighbours (sanity-check for #388 struct misread): " +
                        "activeConnIdLimit=${negotiated.activeConnIdLimit} " +
                        "maxIdleTimeoutMillis=${negotiated.maxIdleTimeoutMillis} " +
                        "initialMaxData=${negotiated.initialMaxData} " +
                        "maxAckDelayMillis=${negotiated.maxAckDelayMillis} " +
                        "maxUdpPayloadSize=${negotiated.maxUdpPayloadSize}",
                )
                // Retry: a peer that permits migration may simply not have issued a spare CID yet
                // right after the handshake, which surfaces as NoSpareConnectionId rather than a
                // refusal. Distinguishing "will never migrate" from "not yet" needs more than one look.
                repeat(6) { attempt ->
                    val result = migrate()
                    println("PROBE migrate() attempt=$attempt -> $result")
                    if (result is MigrationResult.Succeeded) return@repeat
                    kotlinx.coroutines.delay(1500)
                }
            }
        }
    }
}
