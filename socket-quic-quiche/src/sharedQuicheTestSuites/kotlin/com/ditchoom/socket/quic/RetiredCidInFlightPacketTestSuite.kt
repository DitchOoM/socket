@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared regression guard for #445: a 1-RTT packet bearing a **retired** destination CID must be
 * discarded, not answered with a CONNECTION_CLOSE.
 *
 * ## The defect
 * quiche's `get_or_create_recv_path_id()` maps a DCID it no longer recognises to
 * `Error::InvalidState`, and `recv()` has only two dispositions for a `recv_single` failure:
 * `Error::Done`, which drops the packet, and everything else, which runs
 * `self.close(false, e.to_wire(), b"")`. `InvalidState.to_wire()` is PROTOCOL_VIOLATION (0x0a), so a
 * single unrecognised DCID kills an otherwise healthy connection. RFC 9000 §5.2 requires the
 * opposite: "Packets that are matched to an existing connection are discarded if the packets are
 * inconsistent with the state of that connection."
 *
 * It is reachable in ordinary operation. After a migration the peer retires the CID it used on the
 * old path (RFC 9000 §9.5), but packets it already sent on that path are still in flight; the
 * retirement travels the new — typically faster — path and overtakes them. Measured on real hardware:
 * 2 of 11 migrations on a 116-minute Wi-Fi↔cellular walk, and still 3 of 16 forced handoffs after
 * #441 narrowed the routing race.
 *
 * ## Why this test has to be built the awkward way
 * Three separate gates stand between a test and this code path, and each rules out an easier
 * construction (line numbers are quiche 0.29.3's `lib.rs`, checked rather than assumed):
 *
 *  1. **A synthetic packet cannot reach it.** The CID lookup (3337) runs *after* `decrypt_pkt`
 *     (3312), so anything not sealed with the live 1-RTT keys is discarded before it gets there.
 *  2. **A replayed packet cannot reach it either.** The duplicate check `recv_pkt_num.contains(pn)`
 *     (3323) also precedes the lookup, so a re-sent packet returns `Done` — passing with or without
 *     the fix.
 *  3. **Our own routing table would drop it first.** #441 unregisters a retired SCID from
 *     [SharedQuicheServer]'s DCID→driver map, so once that map has caught up the packet never
 *     reaches quiche at all.
 *
 * So the test holds a *genuine* in-flight packet aside ([HoldbackDatagramChannel], via the
 * production [QuicPortBinding.Shared] seam) and holds the routing table's view of the retirement
 * lagging quiche's own ([LaggingScidRetirementQuicheApi]) — which is not a fiction but precisely the
 * cross-coroutine window #441 narrowed and cannot close, held open long enough to step through.
 *
 * ## Why it is a *shared* suite, not the JVM test it started as
 * What it guards is a patch to quiche's Rust source ([patchQuicheRetiredCidRecvIsDrop] in
 * `build.gradle.kts`), and that patch is applied once but **built separately for every target**. A
 * JVM-only guard proves the patch is correct; it proves nothing about whether *this* platform's
 * `libquiche` actually contains it. That gap is not hypothetical here — an Apple cinterop klib
 * embedding a stale `.a` is a failure this repo has already seen once. Each member of this suite is
 * the standing check that the native its own target links has the fix compiled in.
 *
 * ## Mutation-proven
 * Against a quiche built **without** the patch this fails: `quiche_conn_recv` answers the released
 * packet with [QUICHE_ERR_INVALID_STATE], the server closes with PROTOCOL_VIOLATION, and neither
 * payload round-trips. Re-establish that red run if the patch is ever re-fitted to a new quiche.
 */
abstract class RetiredCidInFlightPacketTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /**
     * Build a server on [binding] whose quiche backend is [api]. Every platform has the seam; only
     * the function that carries it differs (`buildJvmQuicServer` / `buildAppleQuicServer` /
     * `buildLinuxQuicServer`), and none of them is visible from common code.
     */
    internal abstract suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer

    /** Same hook as every other suite: JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * The backend a member wraps. Defaulting this would mean naming a platform's binding in common
     * code, which does not compile; each member names its own.
     */
    protected abstract fun platformQuicheApi(): QuicheApi

    @Test
    fun anInFlightPacketBearingARetiredConnectionIdIsDroppedNotFatal() =
        runQuicTest(timeout = 120.seconds) {
            wrapTestBody {
                val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds)
                val api = LaggingScidRetirementQuicheApi(platformQuicheApi())
                val socket =
                    UdpSocket.bind(
                        "127.0.0.1",
                        0,
                        receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                        bufferFactory = BufferFactory.network(),
                    )
                val channel = HoldbackDatagramChannel(socket)
                val server = buildServer(QuicPortBinding.Shared(channel), testTlsConfig(), opts, api)
                val serverPort = server.port
                val serverJob =
                    launch {
                        server.connections {
                            val stream = acceptStream()
                            while (true) {
                                val data = stream.read(30.seconds)
                                if (data is ReadResult.Data) {
                                    try {
                                        stream.writeFully(data.buffer, 5.seconds)
                                    } finally {
                                        // read transfers ownership; write is zero-copy and takes none.
                                        data.buffer.freeIfNeeded()
                                    }
                                } else {
                                    break
                                }
                            }
                            stream.close()
                        }
                    }
                try {
                    withQuicConnection("127.0.0.1", serverPort, opts, timeout = 30.seconds) {
                        val stream = openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy before the migration")

                        // The CID the client is using on the path it is about to leave. Every packet
                        // still in flight on that path carries it, and the client retires it once moved.
                        val retiringDcid =
                            requireNotNull(channel.lastShortHeaderDcid()) {
                                "no short-header datagram reached the server, so no destination CID could be observed"
                            }
                        channel.holdNextDatagramFor(retiringDcid)

                        // Guarantee traffic on the old path so there is something to withhold; its echo
                        // is read later, after the client has retransmitted it over the new path.
                        stream.writeString("in-flight", 5.seconds)
                        val heldDcid = withTimeout(30.seconds) { channel.awaitHeld() }
                        assertTrue(heldDcid.contentEquals(retiringDcid), "withheld a datagram for the wrong connection id")

                        val migration = migrate()
                        assertTrue(migration is MigrationResult.Succeeded, "expected the migration to succeed, got $migration")

                        // quiche has now processed RETIRE_CONNECTION_ID and forgotten that CID. The
                        // routing table has not, because the spy is gating the readback — which is the
                        // state a real reordered packet finds, for as long as the hop takes.
                        val retiredCount = withTimeout(45.seconds) { api.awaitQuicheRetiredAnScid() }
                        assertTrue(retiredCount > 0, "quiche never retired a source connection id, so nothing is stale")
                        assertTrue(
                            channel.lastShortHeaderDcid()?.contentEquals(retiringDcid) == false,
                            "the client is still using the same destination CID after migrating, so the withheld " +
                                "packet's CID is not actually stale and this test would prove nothing",
                        )

                        // Deliver the packet the network held onto. Unpatched, this is where the server
                        // sends CONNECTION_CLOSE(PROTOCOL_VIOLATION) and the connection dies.
                        api.recordRecvResults()
                        channel.release()
                        // Provoke inbound traffic: the server's reader is parked in receive(), so the
                        // handover happens on the next datagram to arrive. This write is that datagram,
                        // and its echo is also what proves the connection still carries stream data.
                        stream.writeString("after", 5.seconds)
                        withTimeout(30.seconds) { channel.awaitDelivered() }

                        // Read until both payloads are back. A stream read may coalesce or split them,
                        // so the assertion is on the accumulated byte sequence, not chunk boundaries.
                        val expected = "in-flightafter"
                        val echoed = StringBuilder()
                        while (echoed.length < expected.length) {
                            val chunk = stream.read(30.seconds).text()
                            if (chunk == NO_DATA) break
                            echoed.append(chunk)
                        }
                        assertEquals(
                            expected,
                            echoed.toString(),
                            "the connection did not survive a packet bearing a retired connection id. " +
                                "quiche_conn_recv codes since the packet was released: ${api.recvResults} " +
                                "(QUICHE_ERR_INVALID_STATE is $QUICHE_ERR_INVALID_STATE, which recv() turns " +
                                "into a PROTOCOL_VIOLATION connection close)",
                        )
                        // The mechanism, not just the symptom: quiche must have absorbed the withheld
                        // packet rather than rejected the connection id it carries.
                        assertTrue(
                            QUICHE_ERR_INVALID_STATE !in api.recvResults,
                            "quiche rejected a packet with QUICHE_ERR_INVALID_STATE — a retired destination " +
                                "CID reached recv() and was answered with a connection close instead of a " +
                                "discard. recv codes seen: ${api.recvResults}",
                        )
                        stream.close()
                    }
                } finally {
                    api.ungate()
                    serverJob.cancel()
                    server.close()
                }
            }
        }

    private fun ReadResult.text(): String = if (this is ReadResult.Data) buffer.readString(buffer.remaining(), Charset.UTF8) else NO_DATA

    private suspend fun QuicByteStream.writeString(
        payload: String,
        timeout: Duration,
    ) {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, timeout)
    }

    private suspend fun QuicByteStream.echo(payload: String): String {
        writeString(payload, 5.seconds)
        return read(30.seconds).text()
    }

    private companion object {
        /** A read that ended or timed out, kept distinct from any payload the test actually sends. */
        const val NO_DATA = "no_data"
    }
}
