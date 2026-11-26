package com.ditchoom.socket.quic.core.patch

import com.ditchoom.socket.quic.DEFAULT_PATH_LATENCY
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guards for the quiche patches that fix path eviction and abandoned probes. Each test stages one wire order on
 * a real quiche pair; it goes red when the patch it names is reverted alone.
 */
class QuichePatchEvictedPathTests {
    private val env = jvmMigrationSimEnv()

    /**
     * Stream data sent on a path the client has migrated away from still reaches the server when a later
     * probe makes quiche evict that path from its table.
     *
     * The 2026-09-28 iPhone walk: Wi-Fi went dark both ways with 67 STREAM frames sent once on it and not
     * yet acknowledged. The client validated cellular — PATH_RESPONSE carries no ACK, and the server's
     * ACKs went to the dark Wi-Fi — migrated, and retired the Wi-Fi path's connection ID, which made that
     * path `unused()`. The held cellular uplink kept every ACK away for 25 s, during which the next probe
     * found the path table full and quiche evicted the Wi-Fi path with its sent queue. Nothing had declared
     * those packets lost, so nothing retransmitted their frames: the server's stream had a hole for the
     * remaining 6 h of an otherwise healthy connection.
     *
     * Staged here exactly: earlier abandoned probes fill the table, the old path holds the data in flight,
     * no ACK reaches the client between the migration and the evicting probe, and only then does the new
     * path carry traffic both ways.
     */
    @Test
    fun streamDataInFlightOnAnEvictedPathIsStillDelivered() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val oldPath = 0
                val fillers = 1 until SPARE_POOL.toInt()
                val newPath = SPARE_POOL.toInt()
                val evictingProbe = newPath + 1

                fun on(endpoint: Int): (QuichePairByHand.Datagram) -> Boolean = { it.clientEndpoint == endpoint }

                val stream = QuicStreamId(0)

                pair.exchange(DEFAULT_PATH_LATENCY, on(oldPath))
                pair.issueSpareCids(pair.server)
                pair.issueSpareCids(pair.client)
                val prefix = "prefix;"
                assertEquals(prefix.length, pair.streamSend(pair.client, stream, prefix, fin = false))
                pair.exchange(DEFAULT_PATH_LATENCY, on(oldPath))

                // Abandoned probes onto links that never answer, each retired as the driver retires
                // one: the table fills with unused paths, and the server tops the spare pool back up.
                val fillerSeqs = fillers.map { endpoint -> assertIs<ProbeOutcome.Probed>(pair.probe(endpoint)).dcidSeq }
                pair.exchange(DEFAULT_PATH_LATENCY, on(oldPath))
                fillerSeqs.forEach { assertEquals(0, pair.retireDcid(it)) }
                pair.exchange(DEFAULT_PATH_LATENCY, on(oldPath))
                pair.issueSpareCids(pair.server)
                pair.exchange(DEFAULT_PATH_LATENCY, on(oldPath))
                assertEquals(SPARE_POOL, pair.spareDcids(pair.client), "the spare pool must be full before the old path goes dark")
                assertEquals(SPARE_POOL, pair.pathCount(pair.client), "abandoned probes must fill the table to one short of the limit")

                // The old path goes dark both ways with data in flight on it.
                val chunks = (0 until IN_FLIGHT_CHUNKS).map { "chunk-${it.toString().padStart(3, '0')};" }
                var sentOnOld = 0
                chunks.forEach { chunk ->
                    assertEquals(chunk.length, pair.streamSend(pair.client, stream, chunk, fin = false))
                    val datagrams = pair.sends(pair.client)
                    assertTrue(datagrams.all { it.clientEndpoint == oldPath }, "the in-flight data must leave on the old path")
                    sentOnOld += datagrams.size
                    pair.elapse(CHUNK_INTERVAL)
                }

                // The new path validates; its PATH_RESPONSE carries no ACK, and the server's ACKs go to
                // the old path.
                val newSeq = assertIs<ProbeOutcome.Probed>(pair.probe(newPath)).dcidSeq
                pair.exchange(DEFAULT_PATH_LATENCY, on(newPath))
                assertIs<MigrateOutcome.Migrated>(pair.migrate(newPath), "the client must move onto the validated path (dcid $newSeq)")
                assertEquals(0, pair.retireDcid(INITIAL_DCID_SEQ), "the client retires the old path's connection id")
                // The new path's uplink holds everything the client sends now, so no ACK can come back.
                val held = pair.sends(pair.client)
                val tableBeforeEviction = pair.pathTable(pair.client)

                assertIs<ProbeOutcome.Probed>(pair.probe(evictingProbe), "the evicting probe must find room")
                val events = pair.pathEvents(pair.client)
                assertTrue(
                    "Closed(endpoint $oldPath)" in events,
                    "the probe must evict the old path for this scenario to mean anything; events $events, table $tableBeforeEviction",
                )

                // The uplink releases; the new path carries traffic both ways from here on.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(held, pair.server)
                val expected = prefix + chunks.joinToString("")
                var received = ""
                var waited = Duration.ZERO
                while (received.length < expected.length && waited < DELIVERY_BOUND) {
                    pair.exchange(DEFAULT_PATH_LATENCY, on(newPath))
                    received += pair.readText(pair.server, stream).first
                    pair.elapse(DELIVERY_STEP)
                    waited += DELIVERY_STEP
                }

                val missingFrom = expected.indices.firstOrNull { it >= received.length || received[it] != expected[it] }
                assertEquals(
                    expected,
                    received,
                    "the server must read every byte the client wrote within $DELIVERY_BOUND of the uplink releasing; " +
                        "it read ${received.length} of ${expected.length} bytes, diverging at stream offset $missingFrom " +
                        "(the in-flight data is offsets ${prefix.length}..${expected.length - 1}, sent once in $sentOnOld " +
                        "datagrams on the old path, endpoint $oldPath). Client path table before the evicting probe: " +
                        "$tableBeforeEviction; after: ${pair.pathTable(pair.client)}; client path events $events",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * Probes the client abandoned before their PATH_CHALLENGEs reached the server do not leave the server
     * unable to admit a new path.
     *
     * The 2026-09-26 iPhone walk: a held cellular uplink delivered challenges 6–9 s after the client had
     * given up on their probes at 3 s and retired the IDs it probed with. The server created a path for
     * each, linked one of the client's spare IDs to it to answer, and kept both forever: the paths failed
     * validation and lost their source IDs, but held their destination IDs, so none was `unused()`. With
     * the table full, the server dropped every datagram from a new 4-tuple — 1,710 probes over 29 h.
     */
    @Test
    fun lateChallengesFromAbandonedProbesLeaveTheServerRoomForANewPath() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val active = 0
                val abandoned = 1..SPARE_POOL.toInt()
                val healthy = SPARE_POOL.toInt() + 1

                fun replenish() {
                    pair.issueSpareCids(pair.server)
                    pair.issueSpareCids(pair.client)
                }

                fun on(vararg endpoints: Int): (QuichePairByHand.Datagram) -> Boolean = { it.clientEndpoint in endpoints }

                pair.exchange(DEFAULT_PATH_LATENCY, on(active))
                replenish()
                pair.exchange(DEFAULT_PATH_LATENCY, on(active))

                // Each probe's challenges are held on its uplink; the client gives up on it and
                // retires the id it probed with, as the driver does.
                val seqs = abandoned.map { assertIs<ProbeOutcome.Probed>(pair.probe(it), "probe from endpoint $it").dcidSeq }
                val late = mutableListOf<QuichePairByHand.Datagram>()
                var probing = Duration.ZERO
                while (probing < PROBE_ABANDONED_AFTER) {
                    val (held, prompt) = pair.sends(pair.client).partition { it.clientEndpoint in abandoned }
                    late += held
                    pair.elapse(DEFAULT_PATH_LATENCY)
                    pair.deliver(prompt, pair.server)
                    val answers = pair.sends(pair.server)
                    pair.elapse(DEFAULT_PATH_LATENCY)
                    pair.deliver(answers, pair.client)
                    probing += DEFAULT_PATH_LATENCY * 2
                }
                seqs.forEach { assertEquals(0, pair.retireDcid(it)) }
                val retirements = pair.sends(pair.client)

                // The uplink releases: the late challenges first, then the retirements behind them. The
                // server's answers go to sockets the client has closed.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(late, pair.server)
                val pathsAfterLateChallenges = pair.pathCount(pair.server)
                val answers = pair.sends(pair.server).filter(on(active))
                pair.deliver(retirements, pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(answers, pair.client)
                repeat(SETTLE_ROUNDS) {
                    pair.exchange(DEFAULT_PATH_LATENCY, on(active))
                    replenish()
                    pair.elapse(SETTLE_STEP)
                }
                val serverTableBefore = pair.pathTable(pair.server)

                // A probe from a healthy new 4-tuple.
                val probe = pair.probe(healthy)
                var events = emptyList<String>()
                var waited = Duration.ZERO
                while ("Validated(endpoint $healthy)" !in events && waited < DELIVERY_BOUND) {
                    pair.exchange(DEFAULT_PATH_LATENCY, on(active, healthy))
                    replenish()
                    events = events + pair.pathEvents(pair.client)
                    pair.elapse(DELIVERY_STEP)
                    waited += DELIVERY_STEP
                }
                assertTrue(
                    "Validated(endpoint $healthy)" in events,
                    "a probe from a healthy new 4-tuple must validate within $DELIVERY_BOUND " +
                        "(probe ${probe::class.simpleName}); the late challenges left the server " +
                        "$pathsAfterLateChallenges paths; server path table before the probe $serverTableBefore, " +
                        "after ${pair.pathTable(pair.server)}; spare ids held: server ${pair.spareDcids(pair.server)}, " +
                        "client ${pair.spareDcids(pair.client)}; ids left to issue: server ${pair.scidsLeft(pair.server)}, " +
                        "client ${pair.scidsLeft(pair.client)}; server ${pair.closed(pair.server)}, " +
                        "client ${pair.closed(pair.client)}; client path events $events",
                )
            } finally {
                pair.close()
            }
        }
}

private val CHUNK_INTERVAL = 10.milliseconds

private val DELIVERY_BOUND = 10.seconds

private val DELIVERY_STEP = 250.milliseconds

private const val INITIAL_DCID_SEQ = 0L

/**
 * Stream writes the old path holds in flight in [streamDataInFlightOnAnEvictedPathIsStillDelivered],
 * each its own packet, as the walk's 13-byte echo probes were.
 */
private const val IN_FLIGHT_CHUNKS = 24

private val PROBE_ABANDONED_AFTER = 3.seconds

private const val SETTLE_ROUNDS = 40

private val SETTLE_STEP = 250.milliseconds

/**
 * Spare destination CIDs a connection can hold at once: [QuicOptions.activeConnectionIdLimit]
 * minus the one in use. Read from the **shipped default** rather than pinned, unlike the
 * per-platform suites — this sim runs on virtual time, so exercising whatever the library
 * actually ships costs nothing and one fewer constant can drift.
 */
private val SPARE_POOL: Long = QuicOptions(alpnProtocols = listOf("migsim")).activeConnectionIdLimit - 1
