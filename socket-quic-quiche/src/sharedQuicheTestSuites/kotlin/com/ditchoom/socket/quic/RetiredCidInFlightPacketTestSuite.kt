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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
            wrapTestBody { withheldPacketScenario(HoldTarget.TheRetiringDcid, PHASE_BUDGET) }
        }

    /**
     * A stall in this suite names the step it stalled in and what the server's ingress saw, rather than
     * surfacing as a bare `TimeoutCancellationException` from whichever enclosing deadline happened to
     * be armed first. Forced here by withholding for a connection ID the client never uses, so the
     * scenario stalls waiting for the withheld datagram.
     */
    @Test
    fun aStallNamesThePhaseItStalledInAndWhatTheServerSaw() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val stalled =
                    assertFailsWith<PhaseStalled> { withheldPacketScenario(HoldTarget.AnIdTheClientNeverUses, 2.seconds) }
                assertEquals(ScenarioPhase.AwaitingWithheldDatagram, stalled.phase, "stalled in the wrong phase: ${stalled.state}")
                assertTrue(
                    "hold=Armed" in stalled.state && ":Passed(dcid=" in stalled.state,
                    "the stall must report the armed hold and the datagrams that passed it: ${stalled.state}",
                )
            }
        }

    /**
     * Each step runs inside [within] under its own [phaseBudget], and every deadline nested in or around
     * a step is longer than that budget, so whichever step stalls is the one that reports. That includes
     * the stream operations': a scoped read or a write that times out throws its own
     * `TimeoutCancellationException`, which no enclosing step can attribute.
     */
    private suspend fun withheldPacketScenario(
        holdTarget: HoldTarget,
        phaseBudget: Duration,
    ) {
        val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds)
        val streamDeadline = phaseBudget * 2
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
        coroutineScope {
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
                withQuicConnection("127.0.0.1", serverPort, opts, timeout = phaseBudget * ScenarioPhase.entries.size) {
                    val journal = ScenarioJournal(channel, api, (this as QuicheBackedConnection).quicheDriver)
                    val pathWatch = launch { pathState.collect { journal.note("path $it") } }
                    try {
                        val stream = openStream()
                        val before =
                            journal.within(ScenarioPhase.EchoBeforeMigration, phaseBudget) {
                                stream.echo("before", streamDeadline)
                            }
                        assertEquals("before", before, "the connection must be healthy before the migration")

                        // The CID the client is using on the path it is about to leave. Every packet
                        // still in flight on that path carries it, and the client retires it once moved.
                        val retiringDcid =
                            requireNotNull(channel.lastShortHeaderDcid()) {
                                "no short-header datagram reached the server, so no destination CID could be observed"
                            }
                        channel.holdNextDatagramFor(holdTarget.dcid(retiringDcid))

                        // Guarantee traffic on the old path so there is something to withhold; its echo
                        // is read later, after the client has retransmitted it over the new path.
                        val heldDcid =
                            journal.within(ScenarioPhase.AwaitingWithheldDatagram, phaseBudget) {
                                stream.writeString("in-flight", streamDeadline)
                                channel.awaitHeld()
                            }
                        assertTrue(heldDcid.contentEquals(retiringDcid), "withheld a datagram for the wrong connection id")

                        // Freeze the server's routing view from here. Before this line the connection
                        // is established and its spare SCIDs are routed — the migration below switches
                        // to one of them, so gating any earlier would stop the PATH_CHALLENGE at the
                        // demux and there would be no migration to hold a retirement back from.
                        api.gate()
                        val migration = journal.within(ScenarioPhase.Migrating, phaseBudget) { migrate() }
                        journal.note("migrate() -> $migration")
                        assertTrue(migration is MigrationResult.Succeeded, "expected the migration to succeed, got $migration")

                        // quiche has now processed RETIRE_CONNECTION_ID and forgotten that CID. The
                        // routing table has not, because the spy is gating the readback — which is the
                        // state a real reordered packet finds, for as long as the hop takes.
                        val retiredCount =
                            journal.within(ScenarioPhase.AwaitingQuicheRetirement, phaseBudget) { api.awaitQuicheRetiredAnScid() }
                        assertTrue(retiredCount > 0, "quiche never retired a source connection id, so nothing is stale")
                        assertTrue(
                            channel.lastShortHeaderDcid()?.contentEquals(retiringDcid) == false,
                            "the client is still using the same destination CID after migrating, so the withheld " +
                                "packet's CID is not actually stale and this test would prove nothing",
                        )

                        // The premise, asserted rather than assumed: the routing map must STILL route
                        // the CID quiche has already forgotten. If it did not, the released packet
                        // would be dropped at the demux and never reach quiche — which produces exactly
                        // the same green as a working fix, and would make everything below vacuous.
                        assertTrue(
                            server.routesConnectionIdForTest(connectionIdKey(retiringDcid)),
                            "the server has already stopped routing the retired CID, so the released packet " +
                                "cannot reach quiche and this test would prove nothing",
                        )

                        // Deliver the packet the network held onto. Unpatched, this is where the server
                        // sends CONNECTION_CLOSE(PROTOCOL_VIOLATION) and the connection dies.
                        api.recordRecvResults()
                        channel.release()
                        // Provoke inbound traffic: the server's reader is parked in receive(), so the
                        // handover happens on the next datagram to arrive. This write is that datagram,
                        // and its echo is also what proves the connection still carries stream data.
                        journal.within(ScenarioPhase.AwaitingReleasedDelivery, phaseBudget) {
                            stream.writeString("after", streamDeadline)
                            channel.awaitDelivered()
                        }

                        // Read until both payloads are back. A stream read may coalesce or split them,
                        // so the assertion is on the accumulated byte sequence, not chunk boundaries.
                        val expected = "in-flightafter"
                        val echoed =
                            journal.within(ScenarioPhase.ReadingEchoes, phaseBudget) {
                                val echoed = StringBuilder()
                                while (echoed.length < expected.length) {
                                    val chunk = stream.read(streamDeadline) { it.readString(it.remaining(), Charset.UTF8) }.text()
                                    if (chunk == NO_DATA) break
                                    echoed.append(chunk)
                                    journal.note("echoed '$echoed'")
                                }
                                echoed.toString()
                            }
                        assertEquals(
                            expected,
                            echoed,
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
                    } finally {
                        pathWatch.cancel()
                    }
                }
            } finally {
                api.ungate()
                serverJob.cancel()
                server.close()
            }
        }
    }

    /** The routing key the server would hold for [cid] — the same snapshot its demux builds. */
    private fun connectionIdKey(cid: ByteArray): ConnectionIdKey {
        val buf = BufferFactory.deterministic().allocate(cid.size)
        cid.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return ConnectionIdKey.from(buf, offset = 0, length = cid.size)
    }

    private fun ScopedRead<String>.text(): String = if (this is ScopedRead.Data) value else NO_DATA

    private suspend fun QuicByteStream.writeString(
        payload: String,
        timeout: Duration,
    ) {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, timeout)
    }

    private suspend fun QuicByteStream.echo(
        payload: String,
        deadline: Duration,
    ): String {
        writeString(payload, deadline)
        // Scoped read (#538): the bytes are decoded inside the block, so the buffer goes back to
        // the driver's pool instead of waiting for a collector that owns it and never runs.
        return read(deadline) { it.readString(it.remaining(), Charset.UTF8) }.text()
    }

    private companion object {
        /** A read that ended (FIN or RESET_STREAM), kept distinct from any payload the test actually sends. */
        const val NO_DATA = "no_data"

        /** What any one step may take. Every step is milliseconds on a healthy run, on every lane. */
        val PHASE_BUDGET = 15.seconds
    }
}

/** The steps of the withheld-packet scenario, in order — what a [PhaseStalled] names. */
internal enum class ScenarioPhase {
    EchoBeforeMigration,
    AwaitingWithheldDatagram,
    Migrating,
    AwaitingQuicheRetirement,
    AwaitingReleasedDelivery,
    ReadingEchoes,
}

/** Which datagram the scenario asks the server's ingress to withhold. */
private sealed interface HoldTarget {
    fun dcid(retiring: ByteArray): ByteArray

    /** The next datagram on the path the client is about to leave — the scenario itself. */
    data object TheRetiringDcid : HoldTarget {
        override fun dcid(retiring: ByteArray): ByteArray = retiring
    }

    /** A connection ID the server never issued, so nothing is ever withheld and the scenario stalls. */
    data object AnIdTheClientNeverUses : HoldTarget {
        override fun dcid(retiring: ByteArray): ByteArray = ByteArray(retiring.size) { 0xEE.toByte() }
    }
}

/** A [ScenarioPhase] that did not finish within its budget, with everything the scenario could see at that moment. */
internal class PhaseStalled(
    val phase: ScenarioPhase,
    budget: Duration,
    val state: String,
) : AssertionError("stalled in $phase for $budget; $state")

/**
 * What the scenario has seen, for a failure message: the client's path-state transitions and its own
 * notes, plus — read at the moment of the stall — the client driver's state and counters, the server
 * ingress ring and the routing gate.
 */
@OptIn(ExperimentalAtomicApi::class)
private class ScenarioJournal(
    private val channel: HoldbackDatagramChannel,
    private val api: LaggingScidRetirementQuicheApi,
    private val clientDriver: QuicheDriver,
) {
    // Copy-on-write behind a compare-and-set: notes are rare (path transitions, one per phase) and
    // come from more than one coroutine, and java.util.concurrent does not exist on Kotlin/Native.
    private val notes = AtomicReference<List<String>>(emptyList())

    private val startedAt = TimeSource.Monotonic.markNow()

    fun note(line: String) {
        val stamped = "@${startedAt.elapsedNow().inWholeMilliseconds}ms $line"
        notes.update { it + stamped }
    }

    /** Run [block] as [phase]; past [budget] it fails as a [PhaseStalled] carrying [describe]. */
    suspend fun <T : Any> within(
        phase: ScenarioPhase,
        budget: Duration,
        block: suspend CoroutineScope.() -> T,
    ): T {
        note("enter $phase")
        return withTimeoutOrNull(budget, block) ?: throw PhaseStalled(phase, budget, describe())
    }

    private suspend fun describe(): String {
        // A stats read is a command on the driver loop, so a wedged loop is itself the finding.
        val counters =
            withTimeoutOrNull(DRIVER_READ_BUDGET) { "${clientDriver.stats().connStats}" }
                ?: "no answer from the driver loop within $DRIVER_READ_BUDGET"
        return "client: ${notes.load()}; client driver: ${clientDriver.state.value}, $counters; " +
            "${channel.describe()}; ${api.describe()}"
    }

    private companion object {
        val DRIVER_READ_BUDGET = 1.seconds
    }
}
