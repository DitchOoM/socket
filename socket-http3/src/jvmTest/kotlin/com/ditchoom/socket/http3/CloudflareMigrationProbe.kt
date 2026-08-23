package com.ditchoom.socket.http3

import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.MigrationResult
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * SCRATCH — a hand-driven probe against a **real, third-party** quiche deployment. Not part of the
 * automated suite; gated on `H3_PROBE_HOST` so CI never reaches the public internet.
 *
 * ## The question it answers
 * #445 makes an unrecognised destination CID fatal in quiche: `recv()` answers it with
 * PROTOCOL_VIOLATION instead of discarding it (RFC 9000 §5.2). We patch our own quiche, which fixes
 * both ends we build — but not a peer we don't. Cloudflare's edge runs quiche, so the open question
 * is whether **their** deployed build still has it, which decides whether our client needs a
 * mitigation of its own (delaying RETIRE_CONNECTION_ID until the old path has drained).
 *
 * That has never been measured. Everything we know is from our own quiche 0.29.3.
 *
 * ## What this stage can and cannot show
 * This migrates to a fresh **local port on the same interface**, which is the only migration a
 * desktop can perform unattended. Both paths then have near-identical RTT, and the
 * RETIRE_CONNECTION_ID is sent *after* the migration completes — so it has to overtake packets sent
 * earlier over an equally fast path. That is possible only through reordering or a transient queue,
 * so a clean run here is **weak evidence of safety**, while a death here is **strong evidence of
 * exposure**. The conclusive experiment is the device one: a real Wi-Fi↔cellular handoff, where the
 * new path is genuinely faster (measured ~29ms of overtake).
 *
 * So: run this first because it is cheap and can only ever produce a definite "yes, exposed".
 *
 * ```
 * H3_PROBE_HOST=cloudflare-quic.com ./gradlew :socket-http3:jvmTest \
 *   --tests "com.ditchoom.socket.http3.CloudflareMigrationProbe" --rerun -i
 * ```
 *
 * `verifyPeer` is off on purpose: this measures transport behaviour across a path change, not our
 * certificate handling, and pinning a public CA bundle into a scratch probe would add a second thing
 * that can fail for reasons unrelated to the question.
 */
class CloudflareMigrationProbe {
    private fun env(
        name: String,
        fallback: String,
    ): String = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fallback

    @Test
    fun migrateWhileTalkingToARealQuicheDeployment() {
        val host = env("H3_PROBE_HOST", "")
        assumeTrue("hand-driven probe — set H3_PROBE_HOST (see class KDoc)", host.isNotEmpty())
        val port = env("H3_PROBE_PORT", "443").toInt()
        val migrations = env("H3_PROBE_MIGRATIONS", "5").toInt()
        val path = env("H3_PROBE_PATH", "/")

        val options =
            QuicOptions(
                alpnProtocols = listOf(HTTP3_ALPN),
                verifyPeer = false,
                idleTimeout = 30.seconds,
                // Fast keepalive so there is ack-eliciting traffic in flight around the switch; an
                // idle connection has nothing on the old path to strand, which is the easy case.
                keepAliveInterval = 100.milliseconds,
            )

        runBlocking {
            withQuicConnection(host, port, options.forHttp3(), TransportConfig(), timeout = 30.seconds) {
                println("PROBE connected session=${identity.session} wire=${identity.wire} alpn=$negotiatedAlpn")
                val h3 = Http3Connection.bootstrap(this, TransportConfig())
                val first = h3.request(Http3Request(method = "GET", authority = host, path = path))
                println("PROBE GET#0 status=${first.status}")

                repeat(migrations) { i ->
                    // Traffic in flight across the switch: issue a request, then migrate without
                    // waiting for it, so the old path has unacked packets at the moment we leave it.
                    val inFlight = async { h3.request(Http3Request(method = "GET", authority = host, path = path)) }
                    delay(5)
                    val result = migrate()
                    println("PROBE migrate#$i -> $result wire=${identity.wire}")
                    if (result !is MigrationResult.Succeeded) {
                        println("PROBE migrate#$i did not move the connection; stopping")
                        return@repeat
                    }
                    val racing = runCatching { inFlight.await() }
                    println("PROBE  in-flight GET#$i -> ${racing.getOrNull()?.status ?: "FAILED: ${racing.exceptionOrNull()}"}")
                    val after = runCatching { h3.request(Http3Request(method = "GET", authority = host, path = path)) }
                    println("PROBE  post-migration GET#$i -> ${after.getOrNull()?.status ?: "FAILED: ${after.exceptionOrNull()}"}")
                    if (after.isFailure) {
                        println(
                            "PROBE CONNECTION DIED after migration #$i — this is the #445 signature if the cause is a peer PROTOCOL_VIOLATION",
                        )
                        return@withQuicConnection
                    }
                }
                println("PROBE survived $migrations migrations")
            }
        }
    }
}
