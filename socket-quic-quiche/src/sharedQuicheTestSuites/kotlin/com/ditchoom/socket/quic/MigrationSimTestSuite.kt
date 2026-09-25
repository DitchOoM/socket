package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceMigrationTrigger
import com.ditchoom.socket.testkit.trace.TraceSilencePhase
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #449 layer 3: **migration, deterministically.**
 *
 * Before this, `SemanticSim` — the only Tier-B harness — passed
 * [MigrationCapability.BackendCannotMigrate], so no simulated connection had ever moved paths and the
 * whole CID/path lifecycle had zero deterministic coverage. That is why every defect in the family was
 * found on a phone. See [withMigrationSim] for how the harness is built, and
 * `PathValidationVirtualClockTests` for the measurement that makes it tractable.
 */
abstract class MigrationSimTestSuite {
    /**
     * The platform's `libquiche` binding, certificate fixtures and `sockaddr` layout — see
     * [MigrationSimEnv]. Each member names its own; defaulting it would mean naming one platform's
     * binding in code that compiles for all of them.
     */
    internal abstract fun simEnv(): MigrationSimEnv

    /**
     * Same hook as every other suite here: a JVM/Android member turns a missing native into a typed
     * skip, while the Kotlin/Native members fix their binding at compile time through cinterop and
     * leave this the default pass-through — on those platforms these tests always run.
     */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    @Test
    fun aClientMigratesToAFreshPathUnderVirtualTime() =
        runTest {
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 917_324L,
                    // The asymmetry that matters for #445: the path being left is slower than the one
                    // being joined, so the new path's packets can overtake the old path's in-flight ones.
                    primaryImpairment = PathImpairment(latency = 80.milliseconds),
                    probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                ) {
                    awaitSpareDcids()
                    val result = migrate().await()
                    assertTrue(
                        result is MigrationResult.Succeeded,
                        "the client failed to migrate in the sim: $result",
                    )
                    assertTrue(
                        clientPaths().isNotEmpty(),
                        "no probe path was ever opened — the migration reported success without moving",
                    )
                    val probe = pipe.pathAt(clientPaths().last())
                    assertTrue(
                        probe.stats.sentToServer > 0 && probe.stats.sentToClient > 0,
                        "the new path carried no traffic in both directions " +
                            "(toServer=${probe.stats.sentToServer} toClient=${probe.stats.sentToClient}) — " +
                            "a migration that reports success but never uses the path is not a migration",
                    )
                }
            }
        }

    /**
     * **#637: a link that carries the connection but not our expansion size.**
     *
     * RFC 9000 §8.2.1 requires a datagram carrying a PATH_CHALLENGE to be expanded "to at least the
     * smallest allowed maximum datagram size of **1200** bytes"; §8.2.2 says the same for
     * PATH_RESPONSE. *At least* — it is a floor. quiche has no knob for it and instead pads such a
     * datagram to fill whatever output buffer the caller passed, so before the fix every probe went out
     * at [QuicheDriver.MAX_DATAGRAM_SIZE] (1350) and so did the peer's answer.
     *
     * A link whose effective UDP payload MTU sits between the floor and that number is therefore
     * unvalidatable by us while being perfectly able to carry the connection. That is not hypothetical:
     * on the 2026-09-20 iPhone walk, leg 1 connection 2 spent **27 consecutive `PathNotValidated`** on
     * one such link and then died on idle timeout. The paired server qlog shows the other half — seven
     * of the probes *did* arrive and were answered within ~60-80us with a **1350-byte** `path_response`,
     * and not one of those answers reached the phone, while 15,696 datagrams of <= 1200 bytes crossed
     * the same local address without a loss.
     *
     * [LinkMtu.Bounded] is that link, and it is deliberately **not** loss: a lossy path is eventually
     * validated by a retry, and this one can never be. [CONSTRAINED_LINK_MTU] is above the RFC floor
     * and below our expansion size, which is the entire window the defect lives in.
     */
    @Test
    fun aPathValidatesOverALinkThatCarriesTheRfcFloorButNotOurExpansionSize() =
        runTest {
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 637_020L,
                    probeImpairment = {
                        PathImpairment(latency = 35.milliseconds, mtu = LinkMtu.Bounded(CONSTRAINED_LINK_MTU))
                    },
                ) {
                    awaitSpareDcids()
                    val result = withTimeout(GIVE_UP_WINDOW) { migrate().await() }
                    val probe = pipe.pathAt(clientPaths().last())
                    assertTrue(
                        result is MigrationResult.Succeeded,
                        "a link that carries $CONSTRAINED_LINK_MTU-byte datagrams in both directions must be " +
                            "validatable: RFC 9000 §8.2.1 asks for 1200, not ${QuicheDriver.MAX_DATAGRAM_SIZE}. " +
                            "Got $result with ${probe.stats.oversized} datagram(s) swallowed for exceeding the " +
                            "link's MTU. Traffic: ${pipeTraffic()}",
                    )
                    assertEquals(
                        0,
                        probe.stats.oversized,
                        "the handoff succeeded but still put datagrams on the wire that this link cannot " +
                            "carry — path validation must expand to the RFC floor, not past it. " +
                            "Traffic: ${pipeTraffic()}",
                    )
                    assertTrue(
                        probe.stats.sentToClient > 0,
                        "the peer never answered over the constrained link, so nothing here proves the " +
                            "PATH_RESPONSE half shrank too. Traffic: ${pipeTraffic()}",
                    )
                }
            }
        }

    /**
     * **The other half of #637: the floor is for validation, and for nothing else.**
     *
     * The RFC floor is a floor, so "expand everything to 1200" also satisfies §8.2.1 — and would pass
     * [aPathValidatesOverALinkThatCarriesTheRfcFloorButNotOurExpansionSize] while silently costing
     * every payload the difference up to [QuicheDriver.MAX_DATAGRAM_SIZE] forever. quiche expands a
     * datagram only on a path it has not validated, so a connection sitting on its validated initial
     * path must still be filling datagrams to the configured size.
     *
     * Asserted on what the client *offered* the link rather than on what crossed it, because that is
     * the sender's decision and the only thing the send window controls.
     */
    @Test
    fun aValidatedPathIsNotHeldToThePathValidationFloor() =
        runTest {
            wrapTestBody {
                withMigrationSim(simEnv(), seed = 637_021L) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()
                        repeat(BULK_CHUNKS) {
                            val out = BufferFactory.network().allocate(BULK_CHUNK_BYTES)
                            repeat(BULK_CHUNK_BYTES) { i -> out.writeByte((i and 0x7f).toByte()) }
                            out.resetForRead()
                            stream.write(out, 30.seconds)
                            out.freeNativeMemory()
                        }
                        delay(2.seconds)
                        val primary = pipe.paths().first()
                        assertTrue(
                            primary.stats.largestOffered > PATH_VALIDATION_EXPANSION,
                            "a bulk transfer over the connection's own validated path never offered a " +
                                "datagram larger than the path-validation floor " +
                                "($PATH_VALIDATION_EXPANSION; largest ${primary.stats.largestOffered}). The " +
                                "expansion floor has leaked into ordinary data, and every payload now pays " +
                                "for it. Traffic: ${pipeTraffic()}",
                        )
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **#447, reproduced from a blackholed probe path alone — no device, no network, no test seam.**
     *
     * The field condition is an unanswered PATH_CHALLENGE, routine on cellular, which previously
     * needed either a phone or [UnansweredProbeDatagramChannel] (a deliberate hole punched through the
     * production [QuicPortBinding.Shared] surface) to produce. Here it is one [PathImpairment] on the
     * path the driver is about to probe: the path swallows everything both ways, the challenge goes
     * unanswered, and the driver runs out its RFC 9000 §8.2.4 budget.
     *
     * ## Why this asserts the mechanism, not a spare-CID count
     * `available_dcids` is moved by at least three independent effects at once — the probe consuming
     * one, the peer issuing a replacement once our RETIRE_CONNECTION_ID lands, and quiche's own
     * `recv()` refill relinking spares into paths that have none — so a count can read a wrong fix as
     * a success.
     *
     * So the assertion is on the mechanism, and it is unambiguous: **the unanswered probe's path keeps
     * the destination CID it was holding, and retires it — exactly once — when the next migration
     * replaces it with a fresh socket.** Pre-#447 the driver never read that sequence number, so no exit
     * could retire it — mutated back, the replacement makes no retire call and this goes red with an
     * empty `retireCalls`.
     *
     * The scenario costs 0ms of wall clock; the ~3s abandon budgets are virtual.
     */
    @Test
    fun anUnansweredProbeKeepsItsConnectionIdUntilAFreshSocketReplacesIt() =
        runTest {
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 77_001L,
                    probeImpairment = { PathImpairment(reach = LinkReach(PathReach.Dark)) },
                ) {
                    awaitSpareDcids(count = SPARE_POOL)
                    assertTrue(clientAvailableDcids() > 1, "the pool must afford a fresh socket for the replacement to happen")

                    val result = withTimeout(120.seconds) { migrate().await() }
                    assertTrue(
                        result !is MigrationResult.Succeeded,
                        "the probe path is a blackhole, so this migration must not have succeeded: $result. " +
                            "If it did, the impairment is not reaching the path and the test proves nothing.",
                    )
                    val probe = pipe.pathAt(clientPaths().last())
                    assertTrue(
                        probe.stats.blackholed > 0,
                        "no datagram was ever blackholed on the probe path — the PATH_CHALLENGE was not " +
                            "actually suppressed, so nothing here reproduces #447",
                    )
                    assertEquals(
                        emptyList(),
                        clientAudit.retireCalls,
                        "the unanswered probe's path is kept for the next probe on its link, so its id must not be retired yet",
                    )

                    val replaced = withTimeout(120.seconds) { migrate().await() }
                    assertTrue(replaced !is MigrationResult.Succeeded, "the second probe path is a blackhole too: $replaced")
                    assertEquals(
                        1,
                        clientAudit.retireCalls.size,
                        "replacing the kept probe path must retire the destination CID quiche linked to it, " +
                            "exactly once. Calls seen: ${clientAudit.retireCalls}. Pre-#447 the driver never " +
                            "read that sequence number, so this list is empty and every failed handoff costs " +
                            "one spare CID for the rest of the connection's life.",
                    )
                    val (seq, rc) = clientAudit.retireCalls.single()
                    assertEquals(0, rc, "quiche refused the retirement of dcid seq $seq (rc=$rc)")
                }
            }
        }

    /**
     * **The whole of `FailedProbeConnectionIdTestSuite`, deterministically and with no test seam.**
     *
     * That suite is the standing per-platform guard for #447, and it earns its keep — each target links
     * its own `libquiche`, so it is the only thing that proves the archive Apple embeds and the `.so`
     * Android ships behave. But it needs real sockets, real time, and
     * [UnansweredProbeDatagramChannel] — a deliberate hole punched through the production
     * [QuicPortBinding.Shared] surface to drop datagrams from sources the server has not yet heard.
     *
     * Here the same scenario is a seeded [PathImpairment] and nothing else: exhaust the CID pool with
     * unanswerable probes, keep an echo stream running throughout to prove the original path survives,
     * let the network come good, and require a real migration onto a healthy path — plus the stream
     * surviving it. 0ms of wall clock; every §8.2.4 abandon budget is virtual.
     *
     * ## Two things this got wrong first, both worth keeping written down
     *  1. **Blackhole by switch, never by probe index.** An attempt that answers
     *     [MigrationResult.Unmoved.Failed.NoSpareConnectionId] never opens a path, so attempt number and
     *     probe index drift apart — index-based healing left the supposedly-healthy recovery path a
     *     blackhole and produced a convincing false #447 reproduction. The real suite's
     *     `dropSourcesNotYetSeen()` / `allowEverySource()` is a switch for exactly this reason.
     *  2. **The replenish round trip is real.** Retiring sends RETIRE_CONNECTION_ID and the peer only
     *     then answers NEW_CONNECTION_ID, so the pool is briefly empty by design. The retry below is the
     *     same bounded tolerance the real suite documents, and it cannot mask the defect: unpatched
     *     there is nothing outstanding to replace, so every retry answers NoSpareConnectionId forever.
     */
    @Test
    fun aRunOfUnansweredProbesLeavesTheConnectionAbleToMigrate() =
        runTest {
            // The sim's equivalent of the real suite's drop/allow switch — see the KDoc.
            var blackholeProbes = true
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 88_202L,
                    probeImpairment = { PathImpairment(reach = LinkReach(if (blackholeProbes) PathReach.Dark else PathReach.Open)) },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, 30.seconds)
                            out.freeNativeMemory()
                            val r = stream.read(60.seconds)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"), "the connection must be healthy before any probe")
                        // The whole pool, not just one: the loop below is "spend every spare and one
                        // more", so starting before the peer has issued them all would leave attempts
                        // answering NoSpareConnectionId for a reason the scenario is not about.
                        awaitSpareDcids(count = SPARE_POOL)

                        repeat(FAILED_ATTEMPTS) { attempt ->
                            val result = withTimeout(120.seconds) { migrate().await() }
                            assertTrue(
                                result is MigrationResult.Unmoved,
                                "attempt ${attempt + 1} of $FAILED_ATTEMPTS reported $result, but its " +
                                    "PATH_CHALLENGE was blackholed and could not have been answered — the " +
                                    "impairment is not reaching the path and the rest of this proves nothing",
                            )
                        }
                        assertTrue(
                            pipe.paths().any { it.stats.blackholed > 0 },
                            "no datagram was ever blackholed, so no probe actually went unanswered — this " +
                                "would pass with the leak fully present",
                        )
                        assertEquals(
                            "still-here",
                            echo("still-here"),
                            "the failed migrations must leave the ORIGINAL path working — a connection that " +
                                "died here would make the recovery assertion below meaningless",
                        )

                        blackholeProbes = false // the network comes good again
                        var recovered = withTimeout(120.seconds) { migrate().await() }
                        var retries = 0
                        while (recovered is MigrationResult.Unmoved.Failed.NoSpareConnectionId && retries < REPLENISH_RETRIES) {
                            delay(50.milliseconds)
                            recovered = withTimeout(120.seconds) { migrate().await() }
                            retries++
                        }
                        assertTrue(
                            recovered is MigrationResult.Succeeded,
                            "after $FAILED_ATTEMPTS unanswered probes the connection could no longer migrate " +
                                "onto a healthy path ($recovered after $retries replenish retries). Each failed " +
                                "probe consumed a destination CID that was never retired, so the pool is " +
                                "permanently empty and the peer has no reason to issue more — one bad handoff " +
                                "on real cellular disables migration for the life of the connection (#447)",
                        )
                        assertEquals("after", echo("after"), "the stream did not survive the migration that followed the failed ones")
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **A migration strands in-flight packets on the path it left — reproduced from per-path latency
     * alone, and a measured limit on how far that gets.**
     *
     * ## What this does prove
     * After a migration the client retires the old path's CID (RFC 9000 §9.5), but packets it already
     * committed to that path are still crossing it. Here that is pure physics rather than a test seam:
     * the old path is 40ms and degrades to 2s at handoff — which is *why* one migrates — while the new
     * path is 10ms, so RETIRE_CONNECTION_ID overtakes the stragglers. A datagram then lands at the
     * server bearing a connection ID that `quiche_conn_source_ids` confirms the server no longer holds.
     *
     * That is #445's **precondition**, deterministic and seed-independent, with no
     * [HoldbackDatagramChannel] and no [LaggingScidRetirementQuicheApi]. Removing the latency asymmetry
     * removes it entirely (mutation-checked: the retirement is then observed at ingress index 428 of
     * 430 with nothing following), which matches the 2026-08-22 field measurement that a loopback burst
     * of 12 migrations survives patched and unpatched alike because RTT≈0 leaves no window.
     *
     * ## ⚠️ What it does NOT prove, measured rather than assumed
     * It is **not** a #445 guard. Built against a deliberately unpatched `libquiche`
     * (`ok_or(Error::InvalidState)` restored, dylib rebuilt and verified loaded by sha), this test still
     * **passes**, while `JvmRetiredCidInFlightPacketTests` on that same build fails with the documented
     * signature `quiche_conn_recv codes [57, 88, -6, 43]`. So the stranded packet never reaches
     * `get_or_create_recv_path_id`: it is discarded by one of the gates that sit *before* the CID
     * lookup — `decrypt_pkt` (lib.rs 3312) or the duplicate check `recv_pkt_num.contains(pn)` (3323),
     * both ahead of the lookup at 3337.
     *
     * Which is exactly why `RetiredCidInFlightPacketTestSuite` withholds and replays a *specific*
     * genuine datagram instead of relying on natural reordering, and why that suite remains the guard —
     * per platform, since each target links its own `libquiche`. This test covers the half a
     * simulation can honestly reach; do not let a green here be read as #445 coverage.
     */
    @Test
    fun aMigrationStrandsInFlightPacketsBearingTheRetiredCid() =
        runTest {
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 31_337L,
                    primaryImpairment = PathImpairment(latency = 40.milliseconds),
                    probeImpairment = { PathImpairment(latency = 10.milliseconds) },
                ) {
                    // The server only drains: the traffic that matters is one-way, and must not be paced
                    // by a round trip or nothing is ever in flight when the handoff happens.
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun push(chunks: Int) {
                            repeat(chunks) {
                                val out = BufferFactory.network().allocate(CHUNK_BYTES)
                                repeat(CHUNK_BYTES) { i -> out.writeByte((i and 0x7f).toByte()) }
                                out.resetForRead()
                                stream.write(out, 30.seconds)
                                out.freeNativeMemory()
                            }
                        }
                        push(3)
                        awaitSpareDcids()

                        val trafficJob = client.launch { push(400) }
                        delay(20.milliseconds)
                        // The old path degrades, so whatever the client commits to it from here takes 2s
                        // and lands long after the 10ms new path has carried RETIRE_CONNECTION_ID.
                        pipe.impair(pipe.paths().first().local, PathImpairment(latency = 2.seconds))
                        delay(60.milliseconds)

                        val result = withTimeout(120.seconds) { migrate().await() }
                        assertTrue(result is MigrationResult.Succeeded, "the migration itself failed: $result")
                        delay(3.seconds)
                        trafficJob.cancel()
                        delay(2.seconds)

                        val newPort = clientPaths().last().port
                        val firstAfterRetire = serverIngress.indexOfFirst { it.retiredScidsSeenOnArrival >= 1 }
                        assertTrue(
                            firstAfterRetire >= 0,
                            "the server never observed the peer retiring one of its source CIDs, so the " +
                                "precondition never existed and nothing below means anything",
                        )
                        val stranded =
                            serverIngress.drop(firstAfterRetire).filter { it.fromPort != newPort && it.dcid != null }
                        assertTrue(
                            stranded.isNotEmpty(),
                            "no datagram arrived from the abandoned path after its connection ID was retired " +
                                "— the overtake window never opened. Ingress ${serverIngress.size}, retirement " +
                                "observed at index $firstAfterRetire.",
                        )

                        // The CID those stragglers carry is one the server has genuinely dropped — read
                        // from quiche itself (`quiche_conn_source_ids`), not inferred from a timing proxy.
                        val live = serverSourceIdsHex().toSet()
                        val strandedCids = stranded.mapNotNull { it.dcid }.distinct()
                        assertTrue(
                            strandedCids.any { it !in live },
                            "every stranded datagram carried a CID the server still holds, so none of them " +
                                "was the retired-CID hazard. stranded=$strandedCids live=$live",
                        )

                        assertIs<QuicConnectionState.Established>(serverDriver.state.value, "the server connection died")
                        assertIs<QuicConnectionState.Established>(clientDriver.state.value, "the client connection died")
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **#453, end to end: one lost probe must not cost the connection — with no second network event.**
     *
     * The whole defect lived in the gap between two harnesses. `AutoMigrationReactorTests` drives the
     * real reactor but against a [QuicConnection] double, so "the probe was lost" is a scripted
     * [MigrationResult] rather than a thing that happens; the sim drives real quiche but reached
     * migration only through [MigrationSimScope.migrate], so the *caller* was always the test. Neither
     * could express the field condition, which is about the reactor and the wire at once. This test is
     * that scenario with nothing scripted between them: the real [wireAutoMigration] reactor, the real
     * quiche client and server, and a probe path that swallows PATH_CHALLENGEs.
     *
     * ## The walk, replayed (2026-08-23, ~t=865s)
     *  - the connection is up on Wi-Fi, echoing;
     *  - the phone leaves Wi-Fi — **the old path stops carrying anything**, which is the part a
     *    migration test on healthy loopback can never model, and is why the field connection died
     *    rather than merely failing to move;
     *  - the platform reports cellular **once**, and then reports nothing further for the rest of the
     *    connection, because the handoff is over and the device is sitting still;
     *  - the first probe onto cellular goes unanswered.
     *
     * Everything after `setNetworkId` is therefore the reactor's own doing. The test never touches the
     * monitor again — that single call is the entire input, and asserting on what follows it is
     * asserting on a policy rather than on a script. Pre-#453 the reactor answered `Failed -> Unit`
     * and waited for a `distinctUntilChanged` emission that was already in the past: exactly one
     * attempt, then the connection sits on the dead path until [IDLE_TIMEOUT_IN_THE_FIELD] kills it.
     *
     * ## What makes it fail rather than merely count wrong
     * The echo is the assertion. With the old path blackholed there is no route to the server except a
     * successful migration, so "did the reactor try again" and "did the connection survive" are the
     * same question — which is the honest shape, because #453 was reported as an outage, not as a
     * counter. The reactor's own attempt log ([SimClientQuicConnection.attempts]) is asserted too, but
     * only as the *explanation*: it is what turns a red echo into a diagnosis.
     *
     * ⚠️ The link heals by probe *index*, which means something only while every attempt binds and
     * probes a fresh socket — while the pool stays above the one spare a retry keeps in reserve for
     * another link. The old path is dead, so no spare comes back, and the scenario states that
     * precondition out loud by waiting for [LOST_PROBES] + 2 spares below. Every §8.2.4 abandon budget
     * here is virtual, so the whole thing costs 0ms of wall clock.
     */
    @Test
    fun aLostProbeIsRetriedUntilTheConnectionRehomes() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 45_301L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    // By probe *index*, which only advances when a path is really opened — an attempt
                    // that answers NoSpareConnectionId never reaches `openPath`. (Healing by *attempt*
                    // number is the trap `aRunOfUnansweredProbesLeavesTheConnectionAbleToMigrate`
                    // documents: the two counters drift apart the moment one attempt is refused early.)
                    probeImpairment = { index ->
                        PathImpairment(reach = LinkReach(if (index <= LOST_PROBES) PathReach.Dark else PathReach.Open))
                    },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, IDLE_TIMEOUT_IN_THE_FIELD)
                            out.freeNativeMemory()
                            val r = stream.read(IDLE_TIMEOUT_IN_THE_FIELD)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"), "the connection must be healthy on Wi-Fi before the handoff")
                        // Stated as a precondition rather than assumed: every probe up to the healing one
                        // takes a fresh socket and a spare, one more stays in reserve, and none comes
                        // back, because the path that would carry RETIRE_CONNECTION_ID is about to die.
                        awaitSpareDcids(count = (LOST_PROBES + 2).toLong())
                        assertEquals(0, client.attempts.size, "nothing may migrate before the handoff")

                        // --- the handoff, and the last input this test ever supplies ---
                        pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                        monitor.setNetworkId(CELLULAR)

                        val after = runCatching { echo("after") }.getOrElse { "CONNECTION DIED: $it" }
                        assertEquals(
                            "after",
                            after,
                            "the connection never re-homed. Wi-Fi went dark and the platform reported " +
                                "cellular exactly once; from there the reactor is on its own, and after " +
                                "$LOST_PROBES unanswered probe(s) it made ${client.attempts.size} attempt(s) " +
                                "(${client.attempts}) on ${clientPaths().size} probe path(s). One attempt " +
                                "means the reactor is waiting for a network event that already happened, " +
                                "so the connection idles out on a dead path exactly as it did in the " +
                                "field (#453).",
                        )

                        assertTrue(
                            client.attempts.size > LOST_PROBES,
                            "the echo recovered but the attempt log says only ${client.attempts.size} " +
                                "attempt(s) were made against $LOST_PROBES blackholed probe(s) — the " +
                                "recovery cannot have come from the retry under test: ${client.attempts}",
                        )
                        assertTrue(
                            client.attempts.last() is MigrationResult.Succeeded,
                            "the last attempt must be the one that moved the connection: ${client.attempts}",
                        )
                        assertTrue(
                            pipe.paths().any { it.stats.blackholed > 0 },
                            "no datagram was ever blackholed, so no probe actually went unanswered and " +
                                "this would pass against the pre-#453 reactor",
                        )
                        val settled = pipe.pathAt(clientPaths().last())
                        assertTrue(
                            settled.stats.sentToServer > 0 && settled.stats.sentToClient > 0,
                            "the connection reports itself moved but its final path carried no two-way " +
                                "traffic (toServer=${settled.stats.sentToServer} " +
                                "toClient=${settled.stats.sentToClient})",
                        )
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **The other half of the #453 contract: the old path keeps working, and the asking gets cheaper
     * without ever stopping.**
     *
     * Here the handoff is onto a link that is simply *gone*: every probe path is a blackhole, forever,
     * and the platform never reports anything again. Unlike
     * [aLostProbeIsRetriedUntilTheConnectionRehomes] the original path stays **healthy**, so the
     * connection does not die and nothing observable ever tells the reactor to stop. That is the one
     * situation an attempt count was supposed to cover, and the reason there is no attempt count:
     * giving up here means sitting forever on a link the platform says we have left, and a link that
     * is unreachable now may be reachable in five minutes.
     *
     * So the contract is not "it stops" but **"it gets cheaper"** — the backoff decays, asserted as a
     * comparison between two equal windows rather than against the schedule, so retuning the backoff
     * cannot silently become retuning this test. Plus the property `MigrationResult.Unmoved` exists to
     * state: a handoff that could not be made costs the caller nothing, and the original path still
     * round-trips at the end.
     *
     * ⚠️ Every one of those retries is a **real** probe only because of #459. Before that fix an
     * abandoned probe never got its connection id back, so past the pool this loop would have been
     * asking a question quiche answers without opening a socket — cheap, and worthless.
     */
    @Test
    fun aHandoffOntoALinkThatNeverAnswersBacksOffWithoutGivingUp() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 45_302L,
                    quicOptions = quietHandoffOptions(monitor),
                    probeImpairment = { PathImpairment(reach = LinkReach(PathReach.Dark)) },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                // Longer than both quiet windows put together: this scenario spends
                                // minutes of virtual time with nothing on the wire, and an echo server
                                // that gave up during the wait would make the survival assertion below
                                // report the harness rather than the connection.
                                val d = st.read(10.minutes)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, 30.seconds)
                            out.freeNativeMemory()
                            val r = stream.read(10.minutes)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"))
                        awaitSpareDcids()

                        monitor.setNetworkId(CELLULAR)
                        delay(GIVE_UP_WINDOW)
                        val firstWindow = client.attempts.size
                        delay(GIVE_UP_WINDOW)
                        val secondWindow = client.attempts.size - firstWindow

                        assertTrue(
                            client.attempts.none { it is MigrationResult.Succeeded },
                            "every probe path is a blackhole, so no attempt could have succeeded — the " +
                                "impairment is not reaching the paths: ${client.attempts}",
                        )
                        assertTrue(
                            firstWindow >= 2,
                            "only $firstWindow attempt(s) in the first $GIVE_UP_WINDOW — one is #453 " +
                                "itself, a lost probe never retried: ${client.attempts}",
                        )
                        assertTrue(
                            secondWindow in 1 until firstWindow,
                            "the reactor asked $firstWindow time(s) in the first $GIVE_UP_WINDOW and " +
                                "$secondWindow time(s) in the second. Not fewer means the cadence never " +
                                "decays, so an unreachable link costs a probe, a socket and a spare " +
                                "connection id at that rate for the life of the connection; none at all " +
                                "means it has quietly given up and a link that comes back is never " +
                                "taken. Attempts: ${client.attempts}",
                        )

                        assertEquals(
                            "still-here",
                            echo("still-here"),
                            "a handoff that could not be made must cost the caller nothing: the connection " +
                                "never left the original path, and that path is still healthy",
                        )
                        assertIs<QuicConnectionState.Established>(clientDriver.state.value, "the client connection died")
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **Every attempt of a dead-path handoff reaches the network, whatever the peer's connection id
     * limit.**
     *
     * The spare pool is `min(peer limit, own limit) - 1` — three against the walk's peer at 4 — and on
     * a dead active path nothing refills it, because the `RETIRE_CONNECTION_ID` that would earn a
     * replacement rides that path. Were each attempt to spend a spare, the pool would hollow the retry
     * loop out: past it, `QuicheDriver.handleMigrate` answers
     * [MigrationResult.Unmoved.Failed.NoSpareConnectionId] before a socket opens, a truthful answer that
     * sends nothing and gives the handoff no new chance. Retries on the link the last probe is bound to
     * probe that path again instead, so every attempt the idle window allows is a PATH_CHALLENGE on the
     * wire — asserted here per attempt, as datagrams the probe paths carried while it ran, not as
     * sockets opened.
     */
    @Test
    fun everyRetryInTheBudgetReachesTheNetwork() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 45_303L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    serverQuicOptions = walkPeerOptions(),
                    probeImpairment = { PathImpairment(latency = DEFAULT_PATH_LATENCY, reach = LinkReach(PathReach.Dark)) },
                ) {
                    awaitWalkPeerPool()
                    pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                    monitor.setNetworkId(CELLULAR)

                    // Datagrams the probe paths have carried, read as each attempt completes.
                    fun probeTraffic() = pipe.paths().drop(1).sumOf { it.stats.sentToServer }
                    val trafficAtAttempt = mutableListOf<Int>()
                    withTimeout(GIVE_UP_WINDOW) {
                        while (client.attempts.lastOrNull() !is MigrationResult.Unmoved.Impossible) {
                            if (client.attempts.size > trafficAtAttempt.size) trafficAtAttempt += probeTraffic()
                            delay(10.milliseconds)
                        }
                    }

                    val refused = client.attempts.filterIsInstance<MigrationResult.Unmoved.Failed.NoSpareConnectionId>()
                    // The last attempt of a dead-path handoff is the one that discovers the connection
                    // has idled out; it answers Impossible and opens no path, which is the loop ENDING
                    // rather than a retry that failed to reach anywhere. Everything before it must have
                    // become a real probe.
                    val reachedTheDriver = client.attempts.filter { it !is MigrationResult.Unmoved.Impossible }
                    assertTrue(
                        refused.isEmpty(),
                        "${refused.size} attempt(s) were refused for want of a spare connection id before a socket " +
                            "opened, so that much of the retry never reached the network: ${client.attempts}",
                    )
                    assertTrue(
                        reachedTheDriver.all { it == MigrationResult.Unmoved.Failed.PathNotValidated },
                        "every attempt against the live connection must have been a probe the link swallowed: ${client.attempts}",
                    )
                    assertTrue(
                        reachedTheDriver.size > WALK_PEER_SPARES,
                        "only ${reachedTheDriver.size} attempt(s) fit the $IDLE_TIMEOUT_IN_THE_FIELD window, no more " +
                            "than the peer-sized pool of $WALK_PEER_SPARES could fund one socket each, so this " +
                            "measures nothing: ${client.attempts}",
                    )
                    val perAttempt = (listOf(0) + trafficAtAttempt.take(reachedTheDriver.size)).zipWithNext { a, b -> b - a }
                    assertTrue(
                        perAttempt.all { it > 0 },
                        "an attempt completed with no datagram on any probe path — it answered " +
                            "PathNotValidated for a probe that never left. Datagrams per attempt: $perAttempt, " +
                            "attempts=${client.attempts}, traffic=${pipeTraffic()}",
                    )
                }
            }
        }

    /**
     * **What ends a dead-path handoff is the connection's own death, and nothing else.**
     *
     * This is the test that says an attempt count is unnecessary rather than merely undesirable. The
     * old path is dead and never comes back, the link the platform reported never answers, and there is
     * no keepalive — so the only thing that can end the retry loop is the connection reaching its idle
     * timeout, at which point `migrate()` answers
     * [MigrationResult.Unmoved.Impossible.ConnectionClosed] and the observer cancels itself.
     *
     * Two arms with different [QuicOptions.idleTimeout]s. The short one must make strictly fewer
     * attempts, **and both must actually be closed at the end** — that second half is what makes the
     * first mean something, because "fewer attempts" is only evidence about the deadline if the
     * deadline is what stopped it.
     *
     * ⚠️ The measurement that made the count deletable: with the loop unbounded, a dead-path connection
     * made 7 attempts and died at 30.1s under a 30s idle timeout — the same instant a *bounded* one
     * died. RFC 9000 §10.1 restarts the idle timer only on the first ack-eliciting packet sent since
     * the last one received, so repeated PATH_CHALLENGEs cannot postpone it and the zombie connection a
     * count would have been protecting against does not exist.
     */
    @Test
    fun aDeadPathHandoffIsEndedByTheConnectionsOwnDeadline() =
        runTest {
            class Arm(
                val attempts: Int,
                val closed: Boolean,
            )

            suspend fun runArm(idleTimeout: kotlin.time.Duration): Arm {
                val monitor = SimNetworkMonitor.on(WIFI)
                var arm = Arm(-1, false)
                withMigrationSim(
                    simEnv(),
                    seed = 45_304L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = idleTimeout,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    probeImpairment = { PathImpairment(reach = LinkReach(PathReach.Dark)) },
                ) {
                    awaitSpareDcids()
                    // The old path dies with the handoff, as it does in the field — otherwise the
                    // connection survives, nothing ends the loop, and there is no deadline to measure.
                    pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                    monitor.setNetworkId(CELLULAR)
                    // Wait for the connection to actually idle out rather than for a fixed window: the
                    // two arms have different deadlines, and that difference is the whole measurement.
                    withTimeout(idleTimeout * DEADLINE_SLACK) {
                        while (clientDriver.state.value !is QuicConnectionState.Closed) delay(100.milliseconds)
                    }
                    arm = Arm(client.attempts.size, clientDriver.state.value is QuicConnectionState.Closed)
                }
                return arm
            }

            wrapTestBody {
                val short = runArm(SHORT_IDLE_WINDOW)
                val long = runArm(LONG_IDLE_WINDOW)

                assertTrue(
                    short.closed && long.closed,
                    "both arms must have idled out for the comparison below to be about the deadline: " +
                        "short closed=${short.closed}, long closed=${long.closed}",
                )
                assertTrue(
                    short.attempts < long.attempts,
                    "a $SHORT_IDLE_WINDOW idle window produced ${short.attempts} migration attempt(s) " +
                        "and a $LONG_IDLE_WINDOW window ${long.attempts} — the same or fewer, so the " +
                        "retry loop is not running until the connection ends. Something other than the " +
                        "connection's own deadline is stopping it, and whatever that is has to justify " +
                        "itself against a longer window it is refusing to use",
                )
                assertTrue(
                    short.attempts >= 2,
                    "even the short window must fit a retry, or this is #453 again: ${short.attempts}",
                )
            }
        }

    /**
     * **A link that attaches after the peer-sized pool would have been spent is still reached** — the
     * 2026-09-12 iPhone walk, connection 7, replayed against real quiche.
     *
     * quiche issues at most `min(peer active_connection_id_limit, its own)` source CIDs
     * (`Connection::scids_left`), so a client at the shipped 8 talking to a server at 4 holds **three**
     * spares — and the field server was exactly that: the walk's qlog has it advertising
     * `active_connection_id_limit: 4` and issuing `NEW_CONNECTION_ID` 1, 2, 3 and nothing more. Wi-Fi
     * then died (`errno=57` on every send), cellular swallowed three probes from three fresh ports, and
     * with each one's `RETIRE_CONNECTION_ID` stuck behind the dead path the pool was gone by t+10s: four
     * `NoSpareConnectionId` refusals and `local: IdleTimeout` at t+43.3s. The next connection came up
     * over that same cellular link three seconds later, at a 120ms round trip.
     *
     * So the link is keyed by **time**, as the walk's evidence is: dark for [WALK_LINK_ATTACH] after the
     * handoff, then every path on it answers, at the sim's realistic 60ms one-way. A retry on the link
     * the last unanswered probe is still bound to probes that path again with the connection ID it
     * already holds (RFC 9000 §9.5 forbids reuse only across local addresses), so the pool bounds how
     * many *sockets* a dead link costs, never how many *probes*.
     */
    @Test
    fun aLinkThatAttachesAfterThePeerSizedPoolWouldBeSpentIsStillReached() =
        runTest {
            val virtual = this
            val monitor = SimNetworkMonitor.on(WIFI)
            // Decided at the handoff, when the attach instant is known; before it no probe path opens.
            var cellular: PathReach = PathReach.Dark
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 45_305L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    serverQuicOptions = walkPeerOptions(),
                    probeImpairment = { PathImpairment(latency = DEFAULT_PATH_LATENCY, reach = LinkReach(cellular)) },
                ) {
                    val serverJob = launchEchoServer()
                    try {
                        val stream = client.openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy on Wi-Fi before the handoff")
                        awaitWalkPeerPool()

                        pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                        cellular = PathReach.DarkUntil(virtual.currentTime.milliseconds + WALK_LINK_ATTACH)
                        monitor.setNetworkId(CELLULAR)

                        val after = runCatching { stream.echo("after") }.getOrElse { "CONNECTION DIED: $it" }
                        val refused = client.attempts.filterIsInstance<MigrationResult.Unmoved.Failed.NoSpareConnectionId>()
                        assertEquals(
                            "after",
                            after,
                            "the link answered every path on it from $WALK_LINK_ATTACH after the handoff, yet the " +
                                "connection never re-homed. The peer's active_connection_id_limit=$WALK_PEER_CID_LIMIT " +
                                "caps the spare pool at $WALK_PEER_SPARES; ${refused.size} attempt(s) were refused for " +
                                "want of a spare connection id, so the retries on the dark link spent the pool instead " +
                                "of probing again with the id their path already held. ${clientPaths().size} socket(s) " +
                                "opened, attempts=${client.attempts}, state=${clientDriver.state.value}",
                        )
                        assertTrue(
                            refused.isEmpty(),
                            "the connection re-homed, but ${refused.size} attempt(s) on the way were refused for want " +
                                "of a spare connection id: ${client.attempts}",
                        )
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **A run of unanswered probes on a dead link leaves the next link a connection ID to probe with.**
     *
     * The same peer at `active_connection_id_limit` 4 and the same dead Wi-Fi as
     * [aLinkThatAttachesAfterThePeerSizedPoolWouldBeSpentIsStillReached], but the cellular link never
     * answers. The handoff keeps asking on it — more attempts than the three spares could fund one
     * probe each — and then the platform reports a *different* link, bound on a different local
     * address, that answers at once. That is the handoff that matters, and it needs one spare: a probe
     * from a new local address must carry a connection ID never used from another (RFC 9000 §9.5).
     * Every retry on the dead link that took a fresh socket spent one of the three, and none came back,
     * because each `RETIRE_CONNECTION_ID` rode the dead path.
     */
    @Test
    fun aRunOfUnansweredProbesOnADeadLinkLeavesTheNextLinkASpare() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            // Where a fresh socket binds: the platform's default route. Flipped with the monitor below.
            var route = SIM_LINK_HOST
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 45_307L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    serverQuicOptions = walkPeerOptions(),
                    probeHost = { route },
                    probeImpairment = {
                        PathImpairment(
                            latency = DEFAULT_PATH_LATENCY,
                            reach = LinkReach(if (route == SIM_OTHER_LINK_HOST) PathReach.Open else PathReach.Dark),
                        )
                    },
                ) {
                    val serverJob = launchEchoServer()
                    try {
                        val stream = client.openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy on Wi-Fi before the handoff")
                        awaitWalkPeerPool()

                        pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                        monitor.setNetworkId(CELLULAR)
                        withTimeout(IDLE_TIMEOUT_IN_THE_FIELD) {
                            while (client.attempts.size <= WALK_PEER_SPARES) delay(10.milliseconds)
                        }
                        val onTheDeadLink = client.attempts
                        // Read now: once the connection has closed, quiche's connection is freed.
                        val sparesLeft = clientAvailableDcids()
                        route = SIM_OTHER_LINK_HOST
                        monitor.setNetworkId(OTHER_WIFI)

                        val after = runCatching { stream.echo("after") }.getOrElse { "CONNECTION DIED: $it" }
                        assertEquals(
                            "after",
                            after,
                            "the second link answered from the moment the platform reported it, yet the connection " +
                                "never reached it. ${onTheDeadLink.size} attempt(s) on the dead link " +
                                "($onTheDeadLink) left $sparesLeft spare connection id(s) of the " +
                                "$WALK_PEER_SPARES the peer granted; every attempt since: " +
                                "${client.attempts.drop(onTheDeadLink.size)}, state=${clientDriver.state.value}",
                        )
                        assertTrue(
                            onTheDeadLink.none { it is MigrationResult.Unmoved.Failed.NoSpareConnectionId },
                            "the connection re-homed, but the dead link's retries were refused for want of a spare " +
                                "connection id on the way: $onTheDeadLink",
                        )
                        val moved = client.attempts.filterIsInstance<MigrationResult.Succeeded>().first()
                        assertEquals(
                            SIM_OTHER_LINK_HOST,
                            moved.localEndpoint.host,
                            "the connection re-homed, but onto the dead link rather than the one that answered: ${client.attempts}",
                        )
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **A link that holds every probe through its attach and then releases them in order** — the
     * 2026-09-21 iPhone burst: four probes from four fresh ports went unanswered for 4.7–16.1s, then
     * the server received all of them within half a second, in order, and answered each; only the
     * attempt still in flight at the release validated, and the answers to the earlier ones found their
     * sockets closed.
     *
     * Characterised against both peers: at the shipped limit every retry binds a fresh socket and the
     * late answers land on closed ones; at the walk's 4 the retries probe the kept path again, so late
     * answers — PATH_RESPONSEs to challenges quiche has already dropped, and the server's own
     * PATH_CHALLENGEs — land on a socket that is still open. Either way they are harmless: the attempt in
     * flight at the release re-homes the connection, nothing migrates again, and once the retirements
     * cross on the new path the pool is back to the peer's `limit - 1`.
     */
    @Test
    fun aLinkThatHoldsProbesThroughItsAttachReHomesOnTheAttemptInFlightAtTheRelease() =
        runTest {
            for (peer in listOf(migrationSimOptions(idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD), walkPeerOptions())) {
                val virtual = this
                val monitor = SimNetworkMonitor.on(WIFI)
                var cellular: PathReach = PathReach.Dark
                val limit = peer.activeConnectionIdLimit
                wrapTestBody {
                    withMigrationSim(
                        simEnv(),
                        seed = 45_308L,
                        quicOptions =
                            migrationSimOptions(
                                idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                                migration = MigrationPolicy.Automatic,
                                networkMonitor = NetworkMonitorSource.Supplied(monitor),
                            ),
                        serverQuicOptions = peer,
                        probeImpairment = { PathImpairment(latency = DEFAULT_PATH_LATENCY, reach = LinkReach(cellular)) },
                    ) {
                        val serverJob = launchEchoServer()
                        try {
                            val stream = client.openStream()
                            assertEquals(
                                "before",
                                stream.echo("before"),
                                "peer limit $limit: the connection must be healthy before the handoff",
                            )
                            awaitSpareDcids(count = limit - 1)

                            pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                            cellular = PathReach.HeldUntil(virtual.currentTime.milliseconds + LINK_HOLD)
                            monitor.setNetworkId(CELLULAR)

                            val after = runCatching { stream.echo("after") }.getOrElse { "CONNECTION DIED: $it" }
                            assertEquals(
                                "after",
                                after,
                                "peer limit $limit: the link released every probe at $LINK_HOLD, yet nothing re-homed: " +
                                    "attempts=${client.attempts}, traffic=${pipeTraffic()}",
                            )
                            val moved = client.attempts.indexOfFirst { it is MigrationResult.Succeeded }
                            assertTrue(
                                moved >= 2 && client.attempts.take(moved).all { it == MigrationResult.Unmoved.Failed.PathNotValidated },
                                "peer limit $limit: every attempt before the release must have been a probe the link " +
                                    "held past its budget, and the link must have held more than one: ${client.attempts}",
                            )

                            delay(REPLENISH_SETTLE)
                            val earlyProbes = pipe.paths().drop(1).take(moved)
                            assertTrue(
                                earlyProbes.any { it.stats.sentToClient > 0 },
                                "peer limit $limit: the server never answered a held probe, so no late answer reached " +
                                    "the client and this proves nothing about them: ${pipeTraffic()}",
                            )
                            assertEquals(
                                moved + 1,
                                client.attempts.size,
                                "peer limit $limit: a late answer moved the connection again: ${client.attempts}",
                            )
                            assertEquals(
                                "still-here",
                                stream.echo("still-here"),
                                "peer limit $limit: the late answers broke the connection",
                            )
                            assertEquals(
                                limit - 1,
                                clientAvailableDcids(),
                                "peer limit $limit: the retirements crossed on the new path, so the pool must be back " +
                                    "at the peer's limit - 1",
                            )
                            stream.close()
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * **A downlink blackout: the server answers every probe and the phone hears none of it** — the
     * 2026-09-20 iPhone walk, leg 3, connection 1, replayed against real quiche.
     *
     * The phone's trace alone reads as a dead link: the platform reported cellular, six probe paths
     * went unvalidated, and the connection closed on `local: IdleTimeout` 30 s after its last datagram
     * in. The server's qlog shows the other half. All 17 PATH_CHALLENGE datagrams from those six ports
     * reached the server, which answered each within 0.1 ms with a PATH_RESPONSE and an ACK and
     * challenged each new path itself. Nothing it sent reached the phone, whatever its size.
     *
     * So every path's uplink is open and its downlink dark, the active one included (in the field the
     * old Wi-Fi socket's sends were failing too; an open uplink there is the harder case, since the
     * server keeps hearing the client). What this pins is what the phone did: its probes go out and
     * are answered, nothing validates, and the connection ends on its own idle deadline with a typed
     * local `IdleTimeout`.
     */
    @Test
    fun aDownlinkBlackoutEndsOnTheIdleDeadlineThoughTheServerAnswersEveryProbe() =
        runTest {
            val virtual = this
            val monitor = SimNetworkMonitor.on(WIFI)
            val downlinkDark =
                PathImpairment(
                    latency = DEFAULT_PATH_LATENCY,
                    reach = LinkReach(uplink = PathReach.Open, downlink = PathReach.Dark),
                )
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 921_003L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    serverQuicOptions = migrationSimOptions(idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD),
                    probeImpairment = { downlinkDark },
                ) {
                    val serverJob = launchEchoServer()
                    try {
                        val stream = client.openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy on Wi-Fi before the blackout")
                        awaitSpareDcids()

                        val primary = pipe.paths().first()
                        pipe.impair(primary.local, downlinkDark)
                        val blackoutAt = virtual.currentTime.milliseconds
                        val heardBefore = primary.stats.deliveredToClient
                        val carriedBefore = primary.stats.deliveredToServer
                        stream.send("after")
                        monitor.setNetworkId(CELLULAR)
                        withTimeout(IDLE_TIMEOUT_IN_THE_FIELD * DEADLINE_SLACK) {
                            while (clientDriver.state.value !is QuicConnectionState.Closed) delay(100.milliseconds)
                        }
                        val closedAfter = virtual.currentTime.milliseconds - blackoutAt

                        val probes = pipe.paths().drop(1)
                        assertTrue(probes.isNotEmpty(), "the handoff never put a probe on the wire: ${client.attempts}")
                        for (probe in probes) {
                            assertTrue(
                                probe.stats.deliveredToServer > 0 && probe.stats.sentToClient > 0,
                                "probe path ${probe.local.port}: the uplink is open, so the server must have received " +
                                    "the probe and answered it. Traffic: ${pipeTraffic()}",
                            )
                            assertEquals(
                                0,
                                probe.stats.deliveredToClient,
                                "probe path ${probe.local.port}: the downlink is dark, yet the client heard the server",
                            )
                        }
                        assertTrue(
                            primary.stats.deliveredToServer > carriedBefore,
                            "the active path's uplink is open, so what the client sent after the blackout must have " +
                                "reached the server. Traffic: ${pipeTraffic()}",
                        )
                        assertEquals(
                            heardBefore,
                            primary.stats.deliveredToClient,
                            "the active path delivered to the client after its downlink went dark",
                        )
                        assertTrue(
                            client.attempts.none { it is MigrationResult.Succeeded },
                            "no answer reached the client, so no path can have validated: ${client.attempts}",
                        )
                        val closed = assertIs<QuicConnectionState.Closed>(clientDriver.state.value)
                        assertEquals(
                            QuicCloseReason.ByLocal(QuicError.IdleTimeout),
                            closed.reason,
                            "the walk recorded `local: IdleTimeout`",
                        )
                        assertTrue(
                            closedAfter >= IDLE_TIMEOUT_IN_THE_FIELD,
                            "the connection closed $closedAfter after the blackout, before its idle deadline",
                        )
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **A send stalled behind a held uplink must not cost the connection its downlink** — the 2026-09-25
     * iPhone walk, both lanes.
     *
     * The cellular uplink held packets for 12–47 s while the downlink kept delivering; the server's qlog
     * shows both directions. A send on the active path outlived the driver's stall bound, and the bound
     * closed the active path's socket while quiche went on routing the connection through that 4-tuple.
     * Every later send failed locally with "sink is closed", every server packet sent to it after the
     * hold lifted (ACKs, echo replies, NEW_CONNECTION_ID) was dropped at the closed socket, and the
     * connection died on its idle bound. A fresh connection on a fresh socket worked in 133 ms.
     *
     * Here the primary's sends are withheld past the stall bound with the downlink open. The server
     * pushes on the stream mid-hold, and that push must reach the application during the hold; the echo
     * written before the hold must come back once it lifts, and the connection must still be up.
     */
    @Test
    fun aSendStalledBehindAHeldUplinkLeavesTheActivePathReceiving() =
        runTest {
            val virtual = this
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 925_025L,
                    quicOptions = migrationSimOptions(idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD),
                ) {
                    val push = CompletableDeferred<Unit>()
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            val first = st.read(60.seconds)
                            if (first !is ReadResult.Data) return@launch
                            st.write(first.buffer, 30.seconds)
                            first.buffer.freeIfNeeded()
                            push.await()
                            st.writeText(PUSHED)
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy before the hold")
                        awaitSpareDcids()
                        val sparesBefore = clientAvailableDcids()

                        val primary = pipe.paths().first()
                        val release = virtual.currentTime.milliseconds + UPLINK_HOLD
                        pipe.impair(primary.local, primary.impairment.copy(sendReturn = SendReturn.WithheldUntil(release)))
                        stream.writeText("after")
                        // Past the stall bound: the driver has given up waiting on the wedged send.
                        delay(DEFAULT_SEND_STALL_BOUND + 1.seconds)
                        assertTrue(
                            primary.stats.abandonedSends > 0,
                            "the driver never stopped waiting on a withheld send, so the stall bound was never " +
                                "reached and nothing below says anything about it: ${pipeTraffic()}",
                        )
                        push.complete(Unit)

                        fun evidence() =
                            "abandonedSends=${primary.stats.abandonedSends} " +
                                "arrivedAtClosedSocket=${primary.stats.arrivedAtClosedSocket} " +
                                "sentOnClosedSocket=${primary.stats.sentOnClosedSocket} " +
                                "state=${clientDriver.state.value} traffic=${pipeTraffic()}"

                        val pushed = stream.readText(release - virtual.currentTime.milliseconds - 1.seconds)
                        assertEquals(
                            PUSHED,
                            pushed,
                            "the server's push, sent on the active path while only the UPLINK was held, never " +
                                "reached the application during the hold. A stalled send must not close the " +
                                "socket quiche still routes the active path through: " +
                                "${primary.stats.arrivedAtClosedSocket} server datagram(s) were dropped at the " +
                                "closed socket and ${primary.stats.sentOnClosedSocket} send(s) refused by it. " +
                                evidence(),
                        )
                        assertTrue(
                            virtual.currentTime.milliseconds < release,
                            "the push arrived only after the uplink released at $release: ${evidence()}",
                        )

                        val after = stream.readText(IDLE_TIMEOUT_IN_THE_FIELD)
                        assertEquals(
                            "after",
                            after,
                            "the echo written into the held uplink never came back once it released: ${evidence()}",
                        )
                        assertEquals("still-here", stream.echo("still-here"), "echoes did not resume: ${evidence()}")
                        assertIs<QuicConnectionState.Established>(
                            clientDriver.state.value,
                            "the connection did not survive the hold: ${evidence()}",
                        )
                        assertEquals(0, primary.stats.arrivedAtClosedSocket, "the active path's socket was closed: ${evidence()}")
                        // The spare pool the next handoff draws on: the peer's replacements rode the downlink.
                        awaitSpareDcids(count = sparesBefore)
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    private suspend fun QuicByteStream.writeText(payload: String) {
        val out = BufferFactory.network().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, IDLE_TIMEOUT_IN_THE_FIELD * DEADLINE_SLACK)
        out.freeNativeMemory()
    }

    private suspend fun QuicByteStream.readText(deadline: Duration): String {
        val r = runCatching { read(deadline) }.getOrElse { return "READ FAILED: $it" }
        if (r !is ReadResult.Data) return "NO_DATA ($r)"
        return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
    }

    /** The walk's echo server: every chunk the client sends comes straight back, until the stream ends. */
    private fun MigrationSimScope.launchEchoServer() =
        client.launch {
            val st = server.acceptStream()
            while (true) {
                val d = st.read(60.seconds)
                if (d !is ReadResult.Data) break
                st.write(d.buffer, 30.seconds)
                d.buffer.freeIfNeeded()
            }
        }

    /** Write [payload] without waiting for anything back. */
    private suspend fun QuicByteStream.send(payload: String) {
        val out = BufferFactory.network().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, IDLE_TIMEOUT_IN_THE_FIELD)
        out.freeNativeMemory()
    }

    /**
     * One round trip, bounded past the connection's own deadline, so what ends a failed handoff is the
     * typed close and never this bound.
     */
    private suspend fun QuicByteStream.echo(payload: String): String {
        val out = BufferFactory.network().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, IDLE_TIMEOUT_IN_THE_FIELD * DEADLINE_SLACK)
        out.freeNativeMemory()
        val r = read(IDLE_TIMEOUT_IN_THE_FIELD * DEADLINE_SLACK)
        if (r !is ReadResult.Data) return "NO_DATA ($r)"
        return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
    }

    /** The premise both walk scenarios stand on: a peer at [WALK_PEER_CID_LIMIT] grants [WALK_PEER_SPARES], whatever the client's own limit. */
    private suspend fun MigrationSimScope.awaitWalkPeerPool() {
        awaitSpareDcids(count = WALK_PEER_SPARES)
        delay(REPLENISH_SETTLE)
        assertEquals(
            WALK_PEER_SPARES,
            clientAvailableDcids(),
            "the sim is not the walk: a peer at active_connection_id_limit=$WALK_PEER_CID_LIMIT grants " +
                "${WALK_PEER_CID_LIMIT - 1} spares whatever the client's own limit says",
        )
    }

    /**
     * **What a dead-link handoff must always end in** — the 2026-09-10 iPhone walk, connections 1 and
     * 2, replayed against real quiche.
     *
     * Connection 2's shape, which is connection 1's with one more fact in front: a handoff onto a link
     * that answers succeeds, the old CID's retirement crosses on the new path and the peer's
     * replacement lands, so the pool is back at the peer's `limit - 1` — the refill works. Then the
     * active path dies and the platform reports a link that never answers. What both traces hold and
     * what no fix may change: at least one probe reaches the wire, no attempt succeeds, and the
     * connection ends on its own deadline with a typed `IdleTimeout` — 30s after its last datagram
     * in, as both phones recorded it — never earlier, never as anything but that reason.
     *
     * How many probes that takes is deliberately not pinned here: that is
     * [aLinkThatAttachesAfterThePeerSizedPoolWouldBeSpentIsStillReached]'s question.
     */
    @Test
    fun aDeadLinkHandoffAgainstThePeerTheWalkHadEndsOnItsOwnDeadlineWithATypedIdleTimeout() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 45_306L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    serverQuicOptions = walkPeerOptions(),
                    // The first probe path is the handoff that succeeds; everything after it is the dead link.
                    probeImpairment = { index ->
                        PathImpairment(reach = LinkReach(if (index > 1) PathReach.Dark else PathReach.Open))
                    },
                ) {
                    awaitSpareDcids(count = WALK_PEER_SPARES)

                    monitor.setNetworkId(CELLULAR)
                    withTimeout(IDLE_TIMEOUT_IN_THE_FIELD) {
                        while (client.attempts.isEmpty()) delay(10.milliseconds)
                    }
                    assertIs<MigrationResult.Succeeded>(client.attempts.single(), "the first handoff is onto a live link")
                    awaitSpareDcids(count = WALK_PEER_SPARES)
                    delay(REPLENISH_SETTLE)
                    assertEquals(
                        WALK_PEER_SPARES,
                        clientAvailableDcids(),
                        "the retirement crossed on the live path, so the peer refilled the pool",
                    )

                    val lastDatagramIn = pipe.pathAt(clientPaths().last()).stats.sentToClient
                    pipe.impair(clientPaths().last(), PathImpairment(reach = LinkReach(PathReach.Dark)))
                    monitor.setNetworkId(WIFI)
                    withTimeout(IDLE_TIMEOUT_IN_THE_FIELD * DEADLINE_SLACK) {
                        while (clientDriver.state.value !is QuicConnectionState.Closed) delay(100.milliseconds)
                    }

                    val handoff = client.attempts.drop(1)
                    assertTrue(clientPaths().size > 1, "no probe reached the wire for the dead-link handoff: ${client.attempts}")
                    assertTrue(
                        handoff.none { it is MigrationResult.Succeeded },
                        "nothing answered, so nothing may have moved: ${client.attempts}",
                    )
                    assertEquals(
                        lastDatagramIn,
                        pipe.pathAt(clientPaths().first()).stats.sentToClient,
                        "the dead path delivered something after it died",
                    )
                    val closed = assertIs<QuicConnectionState.Closed>(clientDriver.state.value)
                    assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), closed.reason, "the walk recorded `local: IdleTimeout`")
                }
            }
        }

    /**
     * **#459 — a replaced probe must give its connection id back to the pool.**
     *
     * #447 made every probe path retire its connection id when it is torn down. This is the other half,
     * invisible from that one: we *do* retire, the peer *does* send a replacement, and quiche links the
     * replacement **straight back into the dead probe path**, which pins it un-evictable
     * (`PathMap::unused()` is `!active() && active_dcid_seq.is_none()`) and holding a spare forever.
     *
     * So every failed handoff costs a spare permanently, on a connection where nothing is wrong. After
     * [SPARE_POOL] of them, `migrate()` answers
     * [MigrationResult.Unmoved.Failed.NoSpareConnectionId] for the rest of the connection's life — a
     * phone that fails a handoff a few times has silently lost active migration, and the only symptom
     * is that a later, perfectly good handoff does not happen.
     *
     * With spares to spare each attempt here binds a fresh socket and replaces the previous probe, so
     * every attempt after the first retires one; the last probe is still kept for its next attempt,
     * holding one id. A pool that recovers therefore settles at [SPARE_POOL] minus that one.
     *
     * ## Why the original path is HEALTHY here
     * That is the whole point. On a dead path the retirement cannot cross, so nothing could come back
     * however correct the driver is. Here the retirement lands, the peer replies, and the pool must
     * recover. Anything less than a healthy path would leave the failure explainable by the network.
     *
     * ## Why it drives migrate() by hand
     * [MigrationPolicy.Manual], so the attempt count is the scenario's rather than the reactor's backoff.
     */
    @Test
    fun anAbandonedProbeGivesItsConnectionIdBackToThePool() =
        runTest {
            wrapTestBody {
                // ⚠️ SWEPT OVER RTT, and that is the point. The first cut of this test ran only on the
                // sim's default zero-latency path and passed against a fix that was purely timing —
                // quiche re-arms `request_validation()` from the abandoned path's own loss timer
                // (`Path::on_loss_detection_timeout`), ~75ms after our §8.2.4 abandon, so whether the
                // exclusion held came down to whether an ACK beat that PTO. Measured with only the
                // refill predicate patched: RTT 0/20/60ms all kept 7 spares, RTT 120ms burned
                // 6→5→4→3, i.e. green on loopback and in this simulator, broken on exactly the real
                // cellular path #459 is about. A CID-lifecycle test at RTT≈0 is not a test — the same
                // lesson #445 learned when a loopback burst survived patched and unpatched alike.
                for (latency in POOL_RECOVERY_LATENCIES) {
                    withMigrationSim(
                        simEnv(),
                        seed = 45_900L,
                        quicOptions = migrationSimOptions(idleTimeout = 10.minutes, keepAliveInterval = KEEPALIVE),
                        primaryImpairment = PathImpairment(latency = latency),
                        probeImpairment = { PathImpairment(reach = LinkReach(PathReach.Dark)) },
                    ) {
                        awaitSpareDcids(count = SPARE_POOL)
                        val trajectory = mutableListOf<String>()
                        val outcomes = mutableListOf<MigrationResult>()

                        repeat((SPARE_POOL + 2).toInt()) {
                            val result = withTimeout(200.seconds) { migrate().await() }
                            outcomes += result
                            // The RETIRE_CONNECTION_ID -> NEW_CONNECTION_ID round trip is real and the
                            // original path is healthy, so it completes. Measured at well under this.
                            delay(REPLENISH_SETTLE)
                            trajectory += "${result::class.simpleName}->spares=${clientAvailableDcids()}"
                        }

                        val refused = outcomes.filterIsInstance<MigrationResult.Unmoved.Failed.NoSpareConnectionId>()
                        assertTrue(
                            refused.isEmpty(),
                            "at a one-way path latency of $latency: " +
                                "${refused.size} of ${outcomes.size} probes were refused for want of a spare " +
                                "connection id, on a connection whose original path never stopped working " +
                                "and whose peer replaced every id we retired " +
                                "(retires=${clientAudit.retireCalls.size}, server active scids=" +
                                "${serverActiveScids()}). Each abandoned probe is keeping the replacement " +
                                "linked to its own dead path, so the pool drains once and never refills " +
                                "(#459). Trajectory: $trajectory. Path table: ${clientPathTable()}",
                        )
                        assertEquals(
                            SPARE_POOL - 1,
                            clientAvailableDcids(),
                            "at a one-way path latency of $latency: after ${outcomes.size} unanswered probes " +
                                "and $REPLENISH_SETTLE of settle each, the spare pool is " +
                                "${clientAvailableDcids()} instead of $SPARE_POOL less the one the kept probe " +
                                "path holds. Trajectory: $trajectory",
                        )
                    }
                }
            }
        }

    /**
     * **#574's second life: the path that dies is the one the connection just migrated *to*, and the
     * expiry count is denominated in a round trip quiche has never measured.**
     *
     * [aDeadPathReHomesWhileTheMonitorStillCallsTheLinkHealthy] kills the path a connection was
     * established on, where quiche has a real RTT sample and the four-expiry budget is worth 3.695s.
     * The field is not that. On the walk that followed #574 shipping, dead air was
     * **10.1 / 16.7 / 19.2 / 10.9 / 10.1s** — with per-event round trips of 67 / 143 / 143 / 58 / 187ms,
     * so two of those outages were on paths the application was measuring at under 70ms. The count was
     * never wrong; it fired on exactly the fourth unanswered expiry every time. The expiries were just
     * four times further apart than the ones the shipped scenario measures.
     *
     * ## The mechanism, in one line
     * A migration switches the connection onto a path the peer has validated but not yet **acked** —
     * the server is still replying to the address it was last told about, so the only datagram the new
     * path has carried inbound is the PATH_RESPONSE. quiche takes RTT samples from acks, so the path is
     * still estimated at RFC 9002 §5.1's initial `333ms / 166.5ms`, and its PTO is
     * `333 + 4·166.5 + 25 = 1024ms` rather than the 246ms of the sampled path next to it. Fifteen of
     * those is 15.36s. Measured here across prior-migration counts, and the arithmetic is exact:
     *
     * | prior migrations | srtt / rttvar when it went dark | PTO | detection |
     * |---|---|---|---|
     * | 0 | 120ms / 25.3ms | 246ms | 3.695s |
     * | 1 | **333ms / 166.5ms** | **1024ms** | **15.36s** |
     * | 2 | **333ms / 166.5ms** | **1024ms** | **15.36s** |
     * | 3 | 70ms / 35ms | 235ms | 3.525s |
     *
     * Not monotone in the count — it is not "migrations make it worse". It depends only on whether an
     * ack had reached the path before it died, which is a race the field runs constantly and the
     * shipped scenario never runs at all.
     *
     * ## What this asserts that the sibling cannot
     * The bound it holds is [SILENT_PATH_PATIENCE]'s: the connection must re-home **without** the
     * expiry count ever reaching [SILENT_PATH_EXPIRY_THRESHOLD]. That is the whole of the ceiling, and
     * asserting it in expiries rather than only in time is what makes it mutation-proof — restore the
     * count-only threshold and this run spends four expiries and 15.36s, both of which this test names.
     *
     * ⚠️ **The premise is asserted, not assumed.** Whether the dying path was ever acked is a race, and
     * a run in which it *was* would recover in ~3.5s on the count rule alone and look like a perfectly
     * green ceiling. So the un-sampled round trip is checked at the moment the path goes dark: if a
     * future quiche, or a change to #556's reply-address pinning, gets an ack onto the new path first,
     * this fails as "the scenario no longer reproduces the condition" instead of passing for the wrong
     * reason.
     *
     * ⚠️ Two prior migrations rather than one, so the active path index is 2 and a reader that assumed
     * index 0 is caught: `QuicheCmd.Stats` reads path 0, which is the active path only until the first
     * successful migration, and an earlier cut of this scenario measured 0 expiries through it and sent
     * the investigation after a comparability guard that was never involved. The expiry counts here go
     * through [MigrationSim.clientActivePathExpiries], which searches for the active path exactly as
     * `sampleActivePathLiveness` does.
     */
    @Test
    fun aPathThatDiesBeforeItsRoundTripIsSampledStillReHomesInTime() =
        runTest {
            val scheduler = testScheduler
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 574_101L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, IDLE_TIMEOUT_IN_THE_FIELD)
                            out.freeNativeMemory()
                            val r = stream.read(IDLE_TIMEOUT_IN_THE_FIELD)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"), "the connection must be healthy on Wi-Fi before the path dies")
                        awaitSpareDcids()
                        assertEquals(0, client.attempts.size, "nothing may migrate before the path dies")

                        // The field condition the shipped scenario cannot reach: a path dying on a
                        // connection that has ALREADY migrated, so the active index is not 0 and the
                        // path it points at has never carried an ack.
                        repeat(PRIOR_MIGRATIONS) {
                            assertTrue(
                                migrate().await() is MigrationResult.Succeeded,
                                "the scenario needs $PRIOR_MIGRATIONS successful migrations before it can start",
                            )
                            awaitSpareDcids()
                        }

                        val probedAt = CompletableDeferred<Long>()
                        val readingAtProbe = CompletableDeferred<ActivePathReading>()
                        val probeWatcher =
                            client.launch {
                                clientDriver.pathState.first { it is QuicPathState.Probing }
                                probedAt.complete(scheduler.currentTime)
                                readingAtProbe.complete(clientActivePath())
                            }
                        val atDark =
                            assertIs<ActivePathReading.Read>(
                                clientActivePath(),
                                "quiche reported no active path at the moment the scenario went to kill one, " +
                                    "so there is nothing to blackhole and nothing to measure",
                            )

                        // --- the handoff the platform never reports. The monitor is not touched. ---
                        // Kill whichever path is active NOW, not the original primary.
                        val primary = pipe.pathAt(clientPaths().last())
                        val wentDark = scheduler.currentTime
                        pipe.impair(primary.local, PathImpairment(reach = LinkReach(PathReach.Dark)))

                        val after = runCatching { echo("after") }.getOrElse { "CONNECTION DIED: $it" }
                        val recoveredIn = (scheduler.currentTime - wentDark).milliseconds
                        assertEquals(
                            "after",
                            after,
                            "the connection never re-homed after the path it had just migrated onto went " +
                                "dark. Attempts: ${client.attempts}, probe paths: ${clientPaths().size}, " +
                                "pipe: ${pipeTraffic()}",
                        )
                        assertEquals(
                            WIFI,
                            monitor.state.value.networkId,
                            "this scenario only means anything while the platform stays silent; the " +
                                "monitor's identity changed, so the migration may have come from the " +
                                "link-change trigger that has always existed",
                        )
                        // The premise. An acked path recovers in ~3.5s on the count rule alone, so
                        // without this the ceiling could be dead code and this test still green.
                        assertTrue(
                            atDark.rtt >= UNSAMPLED_RTT_FLOOR,
                            "the path that went dark was already estimated at ${atDark.rtt} / ${atDark.rttvar}, " +
                                "so quiche had an ack from it and the four-expiry budget was worth ~3.5s " +
                                "rather than the 15.36s this scenario exists to bound. Something now gets " +
                                "an ack onto a freshly migrated path before it can die — #556's reply-address " +
                                "pinning is the likely cause — and this test is no longer reproducing #574's " +
                                "field condition, whatever its timings say.",
                        )
                        val detectedIn = (probedAt.await() - wentDark).milliseconds
                        val atProbe =
                            assertIs<ActivePathReading.Read>(
                                readingAtProbe.await(),
                                "quiche reported no active path at the instant probing started, so the " +
                                    "expiry count that produced the verdict cannot be read back",
                            )
                        val expiriesSpent = atProbe.expiries - atDark.expiries
                        probeWatcher.cancel()
                        assertTrue(
                            expiriesSpent < SILENT_PATH_EXPIRY_THRESHOLD,
                            "the connection re-homed after $detectedIn, but it spent $expiriesSpent " +
                                "unanswered expiries doing it — the full $SILENT_PATH_EXPIRY_THRESHOLD. " +
                                "So the count decided this, not $SILENT_PATH_PATIENCE of silence, which " +
                                "means the ceiling is not carrying the un-sampled case and detection here " +
                                "is back to fifteen PTOs of a round trip quiche never measured (15.36s).",
                        )
                        assertTrue(
                            detectedIn < ANDROID_MONITOR_LAG,
                            "the connection re-homed, but it took $detectedIn to start probing — longer " +
                                "than the $ANDROID_MONITOR_LAG Android's own signal took to arrive on the " +
                                "walk, which is the whole of what this trigger is for. (Full recovery, " +
                                "including quiche's retransmission of the data stranded on the dead path, " +
                                "was $recoveredIn.) Attempts: ${client.attempts}",
                        )
                        assertTrue(
                            primary.stats.blackholed > 0,
                            "no datagram was ever blackholed, so the path never actually died and this " +
                                "would pass against the reactor #574 describes",
                        )
                        val settled = pipe.pathAt(clientPaths().last())
                        assertTrue(
                            settled.stats.sentToServer > 0 && settled.stats.sentToClient > 0,
                            "the connection reports itself moved but its final path carried no two-way " +
                                "traffic (toServer=${settled.stats.sentToServer} " +
                                "toClient=${settled.stats.sentToClient})",
                        )
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **The #385 guard: a path that stops answering and then comes back costs nothing —
     * ⚠️ at every RTT, because at one RTT it proved the opposite of what it claimed.**
     *
     * #385 (closed) was the opposite failure to #574 — a 1.02s cellular excursion buying a full path
     * migration — and its closing analysis is the reason this file has no settle window anywhere:
     * *"there is no principled window"*, and a constant chosen to be larger than one recorded blip is
     * not a policy. A data-plane trigger re-opens that question, so it answers it with a conjunction:
     * a run of unanswered loss-detection expiries **and** a floor on how long the run has been going
     * ([SilenceThreshold.isMetBy]).
     *
     * ## Why this test is a sweep, and what the single-latency version hid
     * The first version of the trigger was the expiry count alone, and this test ran only at the sim's
     * default 120ms round trip, where it passed. Sweeping the same scenario over `primaryImpairment`
     * and changing nothing else:
     *
     * | one-way latency | 2ms | 10ms | 20ms | 40ms | 60ms | 120ms |
     * |---|---|---|---|---|---|---|
     * | migrations bought, count-only | 1 | 1 | 0 | 0 | 0 | 0 |
     *
     * (An independent run of the same sweep also lost the 20ms arm — the boundary moves with the
     * seeded arrival order, which is itself the argument: a threshold whose safety depends on where
     * that boundary happens to land is not a threshold.)
     *
     * RFC 9002 §6.2.1's PTO carries `max_ack_delay` — quiche's 25ms default, which this library never
     * overrides — so it cannot fall below ~26ms however fast the path is, and fifteen of them bottom
     * out under half a second. The "scales with the path" claim was true only on the right-hand side of
     * that table, and #385's trace was an **iPhone on Wi-Fi**: 10–30ms to a CDN edge, squarely on the
     * left. One latency is why it was invisible, so this now runs [BLIP_LATENCIES].
     *
     * ## Why it is not vacuous
     * A blip that never stalled anything would pass for free, so two independent facts are asserted per
     * arm: the pipe really swallowed datagrams, and the echo really did not complete until after the
     * heal. Neither is read from the predicate under test — they are the substrate's own counter and
     * the test scheduler's own clock — which is the difference between a guard and a decoration.
     */
    @Test
    fun aDeadPathReHomesWhileTheMonitorStillCallsTheLinkHealthy() =
        runTest {
            val scheduler = testScheduler
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 574_101L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, IDLE_TIMEOUT_IN_THE_FIELD)
                            out.freeNativeMemory()
                            val r = stream.read(IDLE_TIMEOUT_IN_THE_FIELD)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"), "the connection must be healthy on Wi-Fi before the path dies")
                        awaitSpareDcids()
                        assertEquals(0, client.attempts.size, "nothing may migrate before the path dies")

                        // The walk's own metric is "last echo that completed → `PATH Probing`", so the
                        // sim measures the same edge rather than a proxy for it — and, beside it,
                        // quiche's own expiry counter at that instant, which is the only bound that
                        // can see a whole doubling being lost. See the KDoc.
                        val probedAt = CompletableDeferred<Long>()
                        val expiriesAtProbe = CompletableDeferred<Long>()
                        val probeWatcher =
                            client.launch {
                                clientDriver.pathState.first { it is QuicPathState.Probing }
                                probedAt.complete(scheduler.currentTime)
                                expiriesAtProbe.complete(clientDriver.stats().pathStats?.totalPtoCount ?: -1L)
                            }
                        val expiriesAtDark = clientDriver.stats().pathStats?.totalPtoCount ?: -1L

                        // --- the handoff the platform never reports. The monitor is not touched. ---
                        val primary = pipe.paths().first()
                        val wentDark = scheduler.currentTime
                        pipe.impair(primary.local, PathImpairment(reach = LinkReach(PathReach.Dark)))

                        val after = runCatching { echo("after") }.getOrElse { "CONNECTION DIED: $it" }
                        val recoveredIn = (scheduler.currentTime - wentDark).milliseconds
                        assertEquals(
                            "after",
                            after,
                            "the connection never re-homed. The Wi-Fi path went dark and the platform " +
                                "kept reporting the same link, so the reactor was never handed a network " +
                                "event — which is exactly the $ANDROID_MONITOR_LAG of dead air the walk " +
                                "measured (#574). Attempts: ${client.attempts}, probe paths: " +
                                "${clientPaths().size}, pipe: ${pipeTraffic()}",
                        )
                        assertEquals(
                            WIFI,
                            monitor.state.value.networkId,
                            "this scenario only means anything while the platform stays silent; the " +
                                "monitor's identity changed, so the migration may have come from the " +
                                "link-change trigger that has always existed",
                        )
                        assertTrue(
                            client.attempts.isNotEmpty() && client.attempts.last() is MigrationResult.Succeeded,
                            "the echo came back but the reactor's attempt log does not end in a success, " +
                                "so the recovery did not come from a migration: ${client.attempts}",
                        )
                        val detectedIn = (probedAt.await() - wentDark).milliseconds
                        val expiriesSpent = expiriesAtProbe.await() - expiriesAtDark
                        probeWatcher.cancel()
                        assertEquals(
                            SILENT_PATH_EXPIRY_THRESHOLD,
                            expiriesSpent,
                            "the connection re-homed after $detectedIn, but it spent $expiriesSpent " +
                                "unanswered loss-detection expiries doing it against a threshold of " +
                                "$SILENT_PATH_EXPIRY_THRESHOLD. One too many means an expiry was spent " +
                                "establishing the run's floor instead of counting toward it — the " +
                                "baseline the handshake is supposed to take — and a threshold of N " +
                                "silently means N+1. Measured when that baseline was missing: five " +
                                "expiries and 7.635s instead of four and 3.695s, with the whole suite " +
                                "green, which is why this is asserted in expiries and not only in time.",
                        )
                        assertTrue(
                            detectedIn < ANDROID_MONITOR_LAG,
                            "the connection re-homed, but it took $detectedIn to start probing — longer " +
                                "than the $ANDROID_MONITOR_LAG Android's own signal took to arrive on the " +
                                "walk, which is the whole of what this trigger is for. (Full recovery, " +
                                "including quiche's retransmission of the data stranded on the dead path, " +
                                "was $recoveredIn.) Attempts: ${client.attempts}",
                        )
                        assertTrue(
                            primary.stats.blackholed > 0,
                            "no datagram was ever blackholed, so the original path never actually died " +
                                "and this would pass against the reactor #574 describes",
                        )
                        val settled = pipe.pathAt(clientPaths().last())
                        assertTrue(
                            settled.stats.sentToServer > 0 && settled.stats.sentToClient > 0,
                            "the connection reports itself moved but its final path carried no two-way " +
                                "traffic (toServer=${settled.stats.sentToServer} " +
                                "toClient=${settled.stats.sentToClient})",
                        )
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The field case [StandbyLink.KeepCellularReady] exists for: Wi-Fi goes dark, the platform keeps
     * naming it, and every socket the default route opens lands on the dead link. A standby link is
     * already attached, so the data-plane trigger moves onto it at once instead of probing the dead
     * link until the platform catches up.
     *
     * Then the platform does catch up and names the standby link as its default. The connection is
     * already there, so that is no handoff: one migration, not two.
     */
    @Test
    fun aDeadPathMovesOntoAnAttachedStandbyLinkAndStaysWhenThePlatformNamesIt() =
        runTest {
            val scheduler = testScheduler
            val monitor = SimNetworkMonitor.on(WIFI)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 612_301L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    // The Wi-Fi link is dead to every socket the default route opens on it.
                    probeImpairment = { PathImpairment(reach = LinkReach(PathReach.Dark)) },
                    standby = SimStandby.Link(CELLULAR, MutableStateFlow(SimAttach.Attached)),
                ) {
                    val echo = echoOver(this)
                    try {
                        assertEquals("before", echo.round("before"))
                        awaitSpareDcids()

                        val wentDark = scheduler.currentTime
                        pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                        val after = runCatching { echo.round("after") }.getOrElse { "CONNECTION DIED: $it" }
                        val recoveredIn = (scheduler.currentTime - wentDark).milliseconds

                        assertEquals(
                            "after",
                            after,
                            "the connection never re-homed onto the attached standby link. Attempts: " +
                                "${client.attempts}, pipe: ${pipeTraffic()}",
                        )
                        assertEquals(WIFI, monitor.state.value.networkId, "the platform must still be naming the dead link")
                        val moved = assertIs<MigrationResult.Succeeded>(client.attempts.single(), "attempts: ${client.attempts}")
                        assertEquals(
                            SIM_OTHER_LINK_HOST,
                            moved.localEndpoint.host,
                            "the move must land on the standby link, not on another socket on the dead one",
                        )
                        assertTrue(
                            recoveredIn < ANDROID_MONITOR_LAG,
                            "re-homing took $recoveredIn, no faster than the platform noticing ($ANDROID_MONITOR_LAG)",
                        )

                        // The platform catches up and names the standby link as its default.
                        monitor.setNetworkId(CELLULAR)
                        assertEquals("settled", echo.round("settled"))
                        delay(BLIP_SETTLE)
                        assertEquals(
                            1,
                            client.attempts.size,
                            "the platform naming the link the connection already moved to must not move it " +
                                "again: ${client.attempts}",
                        )
                    } finally {
                        echo.stop()
                    }
                }
            }
        }

    /**
     * A standby link that attaches while a data-plane retry is backing off is taken at once. The
     * backoff is waiting for something to change, and somewhere to go is that change.
     */
    @Test
    fun aStandbyLinkThatAttachesDuringABackoffIsTakenWithoutWaitingItOut() =
        runTest {
            val scheduler = testScheduler
            val monitor = SimNetworkMonitor.on(WIFI)
            val attach = MutableStateFlow<SimAttach>(SimAttach.Detached)
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 612_302L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    probeImpairment = { PathImpairment(reach = LinkReach(PathReach.Dark)) },
                    standby = SimStandby.Link(CELLULAR, attach),
                ) {
                    val echo = echoOver(this)
                    try {
                        assertEquals("before", echo.round("before"))
                        awaitSpareDcids()
                        pipe.impair(pipe.paths().first().local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                        // Data in flight on the dead path: what makes it go silent at all.
                        val after = client.async { runCatching { echo.round("after") }.getOrElse { "CONNECTION DIED: $it" } }

                        // Attach just after a failed attempt, while the reactor is in its backoff.
                        withTimeout(IDLE_TIMEOUT_IN_THE_FIELD) {
                            while (client.attempts.size < FAILED_BEFORE_ATTACH) delay(10.milliseconds)
                        }
                        val failed = client.attempts.toList()
                        assertTrue(
                            failed.all { it is MigrationResult.Unmoved.Failed },
                            "every attempt on the dead link must fail: $failed",
                        )
                        val attachedAt = scheduler.currentTime
                        attach.value = SimAttach.Attached
                        withTimeout(IDLE_TIMEOUT_IN_THE_FIELD) {
                            while (client.attempts.size <= FAILED_BEFORE_ATTACH) delay(10.milliseconds)
                        }
                        val tookOver = (scheduler.currentTime - attachedAt).milliseconds

                        val moved =
                            assertIs<MigrationResult.Succeeded>(client.attempts[FAILED_BEFORE_ATTACH], "attempts: ${client.attempts}")
                        assertEquals(SIM_OTHER_LINK_HOST, moved.localEndpoint.host)
                        assertTrue(
                            tookOver < BACKOFF_AFTER_THIRD_ATTEMPT,
                            "the move onto the standby link finished $tookOver after it attached — not before " +
                                "the $BACKOFF_AFTER_THIRD_ATTEMPT backoff it should have cut short",
                        )
                        assertEquals("after", after.await(), "attempts: ${client.attempts}")
                    } finally {
                        echo.stop()
                    }
                }
            }
        }

    /** An echo stream over a sim connection: [round] writes a payload and reads the server's echo of it. */
    private class SimEcho(
        private val stop: () -> Unit,
        private val stream: QuicByteStream,
    ) {
        suspend fun round(payload: String): String {
            val out = BufferFactory.network().allocate(payload.length)
            out.writeString(payload, Charset.UTF8)
            out.resetForRead()
            stream.write(out, IDLE_TIMEOUT_IN_THE_FIELD)
            out.freeNativeMemory()
            val r = stream.read(IDLE_TIMEOUT_IN_THE_FIELD)
            if (r !is ReadResult.Data) return "NO_DATA"
            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
        }

        suspend fun stop() {
            stream.close()
            stop.invoke()
        }
    }

    private suspend fun echoOver(sim: MigrationSimScope): SimEcho {
        val serverJob =
            sim.client.launch {
                val st = sim.server.acceptStream()
                while (true) {
                    val d = st.read(60.seconds)
                    if (d !is ReadResult.Data) break
                    st.write(d.buffer, 30.seconds)
                    d.buffer.freeIfNeeded()
                }
            }
        return SimEcho({ serverJob.cancel() }, sim.client.openStream())
    }

    /**
     * **A path that goes dark under an application that keeps writing is still declared silent within
     * [SILENT_PATH_PATIENCE]** — the 2026-09-24 Samsung walk, v4 connections 1 and 2.
     *
     * RFC 9002 §6.2.1 times the PTO from the *most recent* ack-eliciting send, so a sender that writes
     * more often than its backed-off PTO pushes the next expiry out on every write and quiche's expiry
     * count stops moving. The walk probe writes an echo every 250ms. On connection 1 (PTO 193ms) one
     * expiry fired, the backed-off 386ms never did, and silence was declared 11.6s later, only because
     * the 2700-byte congestion window filled and the writes stopped. On connection 2 (PTO 329ms) no
     * expiry fired at all in 10.9s. The platform reported Offline first.
     *
     * Two arms: [WALK_ECHO_INTERVAL], where the first expiry fires and the second never does, and
     * [FAST_WRITE_INTERVAL], under the path's own PTO, where none fires. The downlink alone is dark and
     * the monitor never moves, so the only trigger that can re-home the connection is the data-plane
     * one. The bound is one write interval (the first write the path fails to answer) plus
     * [SILENT_PATH_PATIENCE], plus [UNANSWERED_WRITE_SLACK] for the probe to be put on the wire.
     */
    @Test
    fun aPathThatGoesDarkUnderAWritingApplicationIsDeclaredSilentWithinThePatience() =
        runTest {
            val scheduler = testScheduler

            class Arm(
                val writeInterval: Duration,
                val detection: Detection,
                val expiriesSpent: Long,
                val attempts: List<MigrationResult>,
            )

            suspend fun runArm(writeInterval: Duration): Arm {
                val monitor = SimNetworkMonitor.on(WIFI)
                val downlinkDark =
                    PathImpairment(
                        latency = DEFAULT_PATH_LATENCY,
                        reach = LinkReach(uplink = PathReach.Open, downlink = PathReach.Dark),
                    )
                lateinit var arm: Arm
                withMigrationSim(
                    simEnv(),
                    seed = 924_001L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    serverQuicOptions = migrationSimOptions(idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD),
                ) {
                    val serverJob = launchEchoServer()
                    try {
                        val stream = client.openStream()
                        assertEquals("before", stream.echo("before"), "the connection must be healthy before the path goes dark")
                        awaitSpareDcids()

                        val probedAt = CompletableDeferred<Long>()
                        val probeWatcher =
                            client.launch {
                                clientDriver.pathState.first { it is QuicPathState.Probing }
                                probedAt.complete(scheduler.currentTime)
                            }
                        val writer =
                            client.launch {
                                while (true) {
                                    stream.send("w")
                                    delay(writeInterval)
                                }
                            }
                        val expiriesAtDark = clientDriver.stats().pathStats?.totalPtoCount ?: 0L
                        val primary = pipe.paths().first()
                        val wentDark = scheduler.currentTime
                        pipe.impair(primary.local, downlinkDark)
                        // Read while the connection is open: a closed one has no path to report.
                        var expiriesSeen = expiriesAtDark
                        withTimeout(IDLE_TIMEOUT_IN_THE_FIELD * DEADLINE_SLACK) {
                            while (!probedAt.isCompleted && clientDriver.state.value !is QuicConnectionState.Closed) {
                                clientDriver.stats().pathStats?.let { expiriesSeen = it.totalPtoCount }
                                delay(50.milliseconds)
                            }
                        }
                        val detection =
                            if (probedAt.isCompleted) {
                                Detection.Probed((probedAt.await() - wentDark).milliseconds)
                            } else {
                                Detection.ClosedFirst
                            }
                        val expiriesSpent = expiriesSeen - expiriesAtDark
                        writer.cancel()
                        probeWatcher.cancel()
                        assertEquals(
                            WIFI,
                            monitor.state.value.networkId,
                            "the platform must stay silent for this to measure the data plane",
                        )
                        arm = Arm(writeInterval, detection, expiriesSpent, client.attempts.toList())
                    } finally {
                        serverJob.cancel()
                    }
                }
                return arm
            }

            wrapTestBody {
                for (writeInterval in listOf(WALK_ECHO_INTERVAL, FAST_WRITE_INTERVAL)) {
                    val arm = runArm(writeInterval)
                    val bound = arm.writeInterval + SILENT_PATH_PATIENCE + UNANSWERED_WRITE_SLACK
                    val detection = arm.detection
                    assertTrue(
                        detection is Detection.Probed && detection.after <= bound,
                        "writing every ${arm.writeInterval}, the dark path was $detection" +
                            ", past the $bound bound, having spent ${arm.expiriesSpent} loss-detection expiries. " +
                            "Each write re-arms the PTO from the latest send (RFC 9002 §6.2.1), so the expiry count " +
                            "stalls and a ceiling judged only on an expiry never gets asked. Attempts: ${arm.attempts}",
                    )
                }
            }
        }

    /**
     * A recorded trace says which of the reactor's two triggers woke a migration.
     *
     * The load-bearing assertion is the negative one: the monitor never moves here, so a trace claiming
     * `LinkChanged` is recording a trigger that did not fire. Asserting the positive alone would pass
     * against a recorder that hardcoded either token.
     */
    @Test
    fun aDataPlaneMigrationRecordsWhichTriggerWokeIt() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            val recorded = mutableListOf<TraceEvent>()
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 574_777L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                            // The production opt-in, so this asserts what a walk writes to disk.
                            trace = QuicTraceCapture({ event -> recorded += event }),
                        ),
                    probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, IDLE_TIMEOUT_IN_THE_FIELD)
                            out.freeNativeMemory()
                            val r = stream.read(IDLE_TIMEOUT_IN_THE_FIELD)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"), "the connection must be healthy before the path dies")
                        awaitSpareDcids()

                        val primary = pipe.paths().first()
                        pipe.impair(primary.local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                        assertEquals("after", echo("after"), "the connection never re-homed: ${client.attempts}")

                        assertEquals(
                            WIFI,
                            monitor.state.value.networkId,
                            "the monitor moved, so this scenario no longer isolates the data-plane trigger",
                        )

                        val migrations = recorded.filterIsInstance<TraceEvent.Migration>()
                        assertTrue(
                            migrations.isNotEmpty(),
                            "the connection re-homed but the trace recorded no migration at all — which is " +
                                "the state the walk rig was in: outcomes in the log, nothing about what " +
                                "caused them. Recorded: ${recorded.size} events",
                        )
                        assertTrue(
                            migrations.all { it.trigger == TraceMigrationTrigger.PathStoppedAnswering },
                            "the platform monitor never moved in this scenario, so every migration here was " +
                                "woken by the data plane. The trace says otherwise, which means the recorded " +
                                "trigger is not the one that fired: ${migrations.map { it.trigger }}",
                        )

                        // The tally behind the trigger: which half of the threshold bound.
                        val declared = recorded.filterIsInstance<TraceEvent.Silence>().filter { it.phase == TraceSilencePhase.Declared }
                        assertTrue(
                            declared.isNotEmpty(),
                            "a migration was triggered by path silence, but no SILENCE Declared line was " +
                                "recorded, so a reader cannot tell what the run actually counted: " +
                                "${recorded.filterIsInstance<TraceEvent.Silence>()}",
                        )
                        assertTrue(
                            declared.any { it.expiries > 0 && it.elapsed > Duration.ZERO },
                            "the declared run carries no tally and no elapsed time, so it records that a " +
                                "verdict was reached without recording anything about why: $declared",
                        )
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * **The #385 guard: a path that stops answering and then comes back costs nothing —
     * ⚠️ at every RTT, because at one RTT it proved the opposite of what it claimed.**
     *
     * #385 (closed) was the opposite failure to #574 — a 1.02s cellular excursion buying a full path
     * migration — and its closing analysis is the reason this file has no settle window anywhere:
     * *"there is no principled window"*, and a constant chosen to be larger than one recorded blip is
     * not a policy. A data-plane trigger re-opens that question, so it answers it with a conjunction:
     * a run of unanswered loss-detection expiries **and** a floor on how long the run has been going
     * ([SilenceThreshold.isMetBy]).
     *
     * ## Why this test is a sweep, and what the single-latency version hid
     * The first version of the trigger was the expiry count alone, and this test ran only at the sim's
     * default 120ms round trip, where it passed. Sweeping the same scenario over `primaryImpairment`
     * and changing nothing else:
     *
     * | one-way latency | 2ms | 10ms | 20ms | 40ms | 60ms | 120ms |
     * |---|---|---|---|---|---|---|
     * | migrations bought, count-only | 1 | 1 | 0 | 0 | 0 | 0 |
     *
     * (An independent run of the same sweep also lost the 20ms arm — the boundary moves with the
     * seeded arrival order, which is itself the argument: a threshold whose safety depends on where
     * that boundary happens to land is not a threshold.)
     *
     * RFC 9002 §6.2.1's PTO carries `max_ack_delay` — quiche's 25ms default, which this library never
     * overrides — so it cannot fall below ~26ms however fast the path is, and fifteen of them bottom
     * out under half a second. The "scales with the path" claim was true only on the right-hand side of
     * that table, and #385's trace was an **iPhone on Wi-Fi**: 10–30ms to a CDN edge, squarely on the
     * left. One latency is why it was invisible, so this now runs [BLIP_LATENCIES].
     *
     * ## Why it is not vacuous
     * A blip that never stalled anything would pass for free, so two independent facts are asserted per
     * arm: the pipe really swallowed datagrams, and the echo really did not complete until after the
     * heal. Neither is read from the predicate under test — they are the substrate's own counter and
     * the test scheduler's own clock — which is the difference between a guard and a decoration.
     */
    @Test
    fun aBlipTheLengthOfThe385ExcursionCostsNoMigration() =
        runTest {
            val scheduler = testScheduler
            wrapTestBody {
                for (latency in BLIP_LATENCIES) {
                    val monitor = SimNetworkMonitor.on(WIFI)
                    withMigrationSim(
                        simEnv(),
                        seed = 574_102L,
                        quicOptions =
                            migrationSimOptions(
                                idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                                migration = MigrationPolicy.Automatic,
                                networkMonitor = NetworkMonitorSource.Supplied(monitor),
                            ),
                        primaryImpairment = PathImpairment(latency = latency),
                        probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                    ) {
                        val serverJob =
                            client.launch {
                                val st = server.acceptStream()
                                while (true) {
                                    val d = st.read(60.seconds)
                                    if (d !is ReadResult.Data) break
                                    st.write(d.buffer, 30.seconds)
                                    d.buffer.freeIfNeeded()
                                }
                            }
                        try {
                            val stream = client.openStream()

                            suspend fun write(payload: String) {
                                val out = BufferFactory.network().allocate(payload.length)
                                out.writeString(payload, Charset.UTF8)
                                out.resetForRead()
                                stream.write(out, IDLE_TIMEOUT_IN_THE_FIELD)
                                out.freeNativeMemory()
                            }

                            suspend fun read(): String {
                                val r = stream.read(IDLE_TIMEOUT_IN_THE_FIELD)
                                if (r !is ReadResult.Data) return "NO_DATA"
                                return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                            }

                            write("before")
                            assertEquals("before", read(), "at $latency: the connection must be healthy before the blip")
                            awaitSpareDcids()

                            // In flight across the blip on purpose: a path with nothing outstanding arms
                            // no loss timer, so a blip over an idle connection would exercise nothing.
                            write("blip")
                            val primary = pipe.paths().first()
                            val wentDark = scheduler.currentTime
                            pipe.impair(primary.local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                            delay(BLIP_385)
                            pipe.impair(primary.local, PathImpairment(latency = latency))
                            val echoed = read()
                            val stalledFor = (scheduler.currentTime - wentDark).milliseconds

                            assertTrue(
                                primary.stats.blackholed >= BLIP_MIN_SWALLOWED,
                                "at $latency: only ${primary.stats.blackholed} datagram(s) were swallowed " +
                                    "during the $BLIP_385 blip, so the data plane was never meaningfully " +
                                    "dark and this guard would pass against any threshold at all",
                            )
                            assertTrue(
                                stalledFor >= BLIP_385,
                                "at $latency: the echo came back after $stalledFor, inside the $BLIP_385 " +
                                    "blip — the impairment is not reaching the path, so nothing is damped",
                            )
                            assertEquals("blip", echoed, "at $latency: the connection must survive a blip on its own path")

                            // Long enough that a trigger armed during the blip would have fired by now.
                            delay(BLIP_SETTLE)
                            assertEquals(
                                0,
                                client.attempts.size,
                                "at a one-way latency of $latency a $BLIP_385 blip that healed bought " +
                                    "${client.attempts.size} migration attempt(s): ${client.attempts}. " +
                                    "That is #385 re-opened — the trigger is reacting to a path that went " +
                                    "quiet rather than to one that stopped answering. Note the latency: a " +
                                    "count of loss-detection expiries alone passes this at 40ms and above " +
                                    "and fails it at 20ms and below, because quiche's 25ms max_ack_delay " +
                                    "floors the PTO. Probe paths opened: ${clientPaths().size}",
                            )
                            assertTrue(
                                clientPaths().isEmpty(),
                                "at $latency: no migration was recorded but ${clientPaths().size} probe " +
                                    "path(s) were opened, so something migrated behind the reactor's back",
                            )
                            write("still-here")
                            assertEquals("still-here", read(), "at $latency: the healed path must still carry traffic")
                            stream.close()
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * **An ordinarily lossy path is not a silent one** — the other way the expiry count can run ahead
     * of the truth.
     *
     * quiche increments `total_pto_count` on *every* loss-detection timer expiry, the time-threshold
     * loss branch included, and that branch does not back off (its own header comment — "PTO count
     * measures the number of loss events and provides a normalized loss metric" — is wrong about this).
     * So on a path that is losing packets but working, the count can reach the threshold far sooner
     * than the PTO-backoff arithmetic suggests, and a count-only trigger would migrate a connection
     * whose only problem is ordinary loss.
     *
     * Nothing is blackholed here at all: the path drops [LOSSY_RATES] of its datagrams and carries the
     * rest, throughout. The echoes completing are the proof that it is working; zero migrations are the
     * assertion. This is the second reason the time floor in [SilenceThreshold.isMetBy] is not optional,
     * and it stacks with the first — see `aBlipTheLengthOfThe385ExcursionCostsNoMigration`.
     */
    @Test
    fun aLossyPathIsNotASilentOne() =
        runTest {
            wrapTestBody {
                for (loss in LOSSY_RATES) {
                    val monitor = SimNetworkMonitor.on(WIFI)
                    withMigrationSim(
                        simEnv(),
                        seed = 574_104L,
                        quicOptions =
                            migrationSimOptions(
                                idleTimeout = 5.minutes,
                                migration = MigrationPolicy.Automatic,
                                networkMonitor = NetworkMonitorSource.Supplied(monitor),
                            ),
                        primaryImpairment = PathImpairment(latency = DEFAULT_PATH_LATENCY, loss = loss),
                        probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                    ) {
                        val serverJob =
                            client.launch {
                                val st = server.acceptStream()
                                while (true) {
                                    val d = st.read(STALL_ECHO_TIMEOUT)
                                    if (d !is ReadResult.Data) break
                                    st.write(d.buffer, 30.seconds)
                                    d.buffer.freeIfNeeded()
                                }
                            }
                        try {
                            val stream = client.openStream()
                            val primary = pipe.paths().first()
                            repeat(LOSSY_ECHOES) { round ->
                                val out = BufferFactory.network().allocate(8)
                                out.writeString("lossy$round", Charset.UTF8)
                                out.resetForRead()
                                stream.write(out, STALL_ECHO_TIMEOUT)
                                out.freeNativeMemory()
                                val r = stream.read(STALL_ECHO_TIMEOUT)
                                assertTrue(
                                    r is ReadResult.Data,
                                    "at ${loss * 100}% loss the connection stopped carrying data at round " +
                                        "$round, so the zero-migration assertion below would prove nothing",
                                )
                                r.buffer.freeIfNeeded()
                                delay(LOSSY_GAP)
                            }
                            assertEquals(
                                0,
                                client.attempts.size,
                                "a working path losing ${loss * 100}% of its datagrams bought " +
                                    "${client.attempts.size} migration attempt(s): ${client.attempts}. " +
                                    "quiche counts every loss-detection expiry, not only backed-off " +
                                    "PTOs, so loss alone can reach the expiry threshold — the elapsed " +
                                    "floor is what keeps that from being a migration. Dropped: " +
                                    "${primary.stats.dropped}",
                            )
                            assertTrue(
                                primary.stats.dropped > 0,
                                "no datagram was dropped at ${loss * 100}% loss, so this arm measured nothing",
                            )
                            stream.close()
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * One run of [BLIP_ROUNDS] separate stalls, each sized by evidence rather than by the clock, under
     * an arbitrary [threshold] — the shape both the guard below and [theSilenceThresholdMarginIsWhereWeThinkItIs]
     * need, differing only in what they assert about the result.
     */
    private class BlipRun(
        /** Migration attempts recorded after each round, in order. */
        val attemptsPerRound: List<Int>,
        val probePathsOpened: Int,
        val datagramsSwallowed: Int,
        val darkMillisPerRound: List<Long> = emptyList(),
        val firesPerRound: List<Long> = emptyList(),
    )

    private suspend fun blipRoundsUnder(threshold: SilenceThreshold): BlipRun {
        val monitor = SimNetworkMonitor.on(WIFI)
        return withMigrationSim(
            simEnv(),
            seed = 574_103L,
            quicOptions =
                migrationSimOptions(
                    // Not the field deadline: quiche's backoff makes each successive stall
                    // longer, and surviving a 30s idle window is the #453 scenario's assertion,
                    // not this one's. Virtual, so the room costs nothing.
                    idleTimeout = 5.minutes,
                    migration = MigrationPolicy.Automatic,
                    networkMonitor = NetworkMonitorSource.Supplied(monitor),
                ),
            probeImpairment = { PathImpairment(latency = 35.milliseconds) },
            silenceThreshold = threshold,
        ) {
            val serverJob =
                client.launch {
                    val st = server.acceptStream()
                    while (true) {
                        val d = st.read(STALL_ECHO_TIMEOUT)
                        if (d !is ReadResult.Data) break
                        st.write(d.buffer, 30.seconds)
                        d.buffer.freeIfNeeded()
                    }
                }
            try {
                val stream = client.openStream()

                suspend fun write(payload: String) {
                    val out = BufferFactory.network().allocate(payload.length)
                    out.writeString(payload, Charset.UTF8)
                    out.resetForRead()
                    stream.write(out, STALL_ECHO_TIMEOUT)
                    out.freeNativeMemory()
                }

                suspend fun read(): String {
                    val r = stream.read(STALL_ECHO_TIMEOUT)
                    if (r !is ReadResult.Data) return "NO_DATA"
                    return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                }

                write("before")
                assertEquals("before", read(), "the connection must be healthy before the first blip")
                awaitSpareDcids()

                // quiche's own cumulative fire count, read through the driver's stats seam —
                // the raw evidence, not the predicate under test. It sizes the stalls; every
                // assertion below is about migrations.
                suspend fun ptoFires(): Long = clientDriver.stats().pathStats?.totalPtoCount ?: 0L

                val primary = pipe.paths().first()
                val attemptsPerRound = mutableListOf<Int>()
                val darkMillis = mutableListOf<Long>()
                val firesPerRound = mutableListOf<Long>()
                repeat(BLIP_ROUNDS) { round ->
                    write("blip$round")
                    val firesBefore = ptoFires()
                    val sched = currentCoroutineContext()[kotlinx.coroutines.test.TestCoroutineScheduler]!!
                    val darkFrom = sched.currentTime
                    pipe.impair(primary.local, PathImpairment(reach = LinkReach(PathReach.Dark)))
                    withTimeout(DARK_ROUND_LIMIT) {
                        while (ptoFires() - firesBefore < DARK_ROUND_PTOS &&
                            (sched.currentTime - darkFrom).milliseconds < BLIP_ROUND_LIMIT
                        ) {
                            delay(50.milliseconds)
                        }
                    }
                    darkMillis += sched.currentTime - darkFrom
                    firesPerRound += ptoFires() - firesBefore
                    pipe.impair(primary.local, PathImpairment(latency = DEFAULT_PATH_LATENCY))
                    assertEquals(
                        "blip$round",
                        read(),
                        "round $round: the path healed but the echo never came back, so the " +
                            "rounds after it would prove nothing",
                    )
                    attemptsPerRound += client.attempts.size
                    // Ordinary traffic between the stalls. Not padding: each stall inflates the path's
                    // own RTT estimate, and a round trip that succeeds is the only thing that walks it
                    // back down — without them the second stall's PTO is already seconds long and the
                    // rounds after it contribute no expiries at all, which would leave the tally this
                    // test is built on below the threshold it has to cross.
                    repeat(BLIP_SETTLE_ECHOES) { settle ->
                        write("settle$round-$settle")
                        assertEquals("settle$round-$settle", read(), "round $round: settling echo $settle never came back")
                    }
                }
                val run =
                    BlipRun(
                        attemptsPerRound.toList(),
                        clientPaths().size,
                        primary.stats.blackholed,
                        darkMillis.toList(),
                        firesPerRound.toList(),
                    )
                stream.close()
                run
            } finally {
                serverJob.cancel()
            }
        }
    }

    /**
     * **"Consecutive" is the whole of the threshold: separate stalls must not add up.**
     *
     * The #385 guard next door shows that one blip of the recorded length is under the line. It does
     * not show that a *second* one starts from zero — and if it did not, the trigger would degenerate
     * into "migrate once this connection has accumulated four PTOs", which every long-lived connection
     * on an ordinarily lossy link reaches sooner or later. That is a worse #385 than #385: not a
     * migration caused by a blip, but a migration caused by nothing in particular, arriving later the
     * healthier the network is.
     *
     * So this runs [BLIP_ROUNDS] stalls with a **completed echo between each pair** — the echo is the
     * proof that the path answered, taken from the connection rather than from any counter the trigger
     * owns. Each stall is held dark until quiche's own `total_pto_count` has advanced by
     * [DARK_ROUND_PTOS], so every round is the same size wherever the backoff has got to, and three of
     * them are comfortably past a threshold that no single one reaches.
     *
     * ⚠️ **A fixed-length blip cannot express this, and the first cut of this test was decorative
     * because of it.** RFC 9002 §6.2.1 doubles the PTO on each consecutive fire and quiche does not
     * always bring it back down between stalls: measured here, the timer was 246ms entering the first
     * stall, 1.590s entering the second and 2.355s entering the third — so three 1.02s blips contained
     * *two* expiries in total, all of them in round one, and a lifetime tally sailed through. Sizing
     * the stall by the evidence rather than by the clock is what makes the mutation fail.
     */
    @Test
    fun separateBlipsDoNotAccumulateIntoAMigration() =
        runTest {
            wrapTestBody {
                val run = blipRoundsUnder(SILENT_PATH_THRESHOLD)
                run.attemptsPerRound.forEachIndexed { round, attempts ->
                    assertEquals(
                        0,
                        attempts,
                        "after ${round + 1} separate stall(s), each of them under the threshold " +
                            "on its own, the connection migrated. The run of " +
                            "unanswered expiries is not being cleared when the path answers, so it " +
                            "is a lifetime tally rather than a consecutive one — every long-lived " +
                            "connection reaches the threshold eventually, on no evidence at all. " +
                            "dark ms per round: ${run.darkMillisPerRound}",
                    )
                }
                // The two premises, because this test spent a release asserting neither. Without the
                // first it can pass with stalls too short to produce any evidence to accumulate;
                // without the second it can pass with stalls so long that "must not migrate" is the
                // wrong answer — which is what it did, at 20.3s. See [BLIP_ROUND_LIMIT].
                assertTrue(
                    run.firesPerRound.sum() >= SILENT_PATH_EXPIRY_THRESHOLD,
                    "the stalls produced ${run.firesPerRound} unanswered expiries in total, which never " +
                        "reaches the $SILENT_PATH_EXPIRY_THRESHOLD a lifetime tally would have had to " +
                        "cross — so a tally and a run are indistinguishable in this run and zero " +
                        "migrations proves nothing about which one the driver keeps",
                )
                run.darkMillisPerRound.forEachIndexed { round, dark ->
                    assertTrue(
                        dark.milliseconds <= BLIP_ROUND_LIMIT,
                        "round $round was dark for ${dark.milliseconds}, past the $BLIP_ROUND_LIMIT that " +
                            "keeps these stalls blips. A stall longer than $SILENT_PATH_PATIENCE *should* " +
                            "re-home — that is #574 — so asserting no migration across one asserts the " +
                            "defect. Rounds: ${run.darkMillisPerRound}",
                    )
                }
                assertTrue(
                    run.datagramsSwallowed >= BLIP_MIN_SWALLOWED * BLIP_ROUNDS,
                    "only ${run.datagramsSwallowed} datagram(s) were swallowed across " +
                        "$BLIP_ROUNDS stalls, so the data plane was never meaningfully dark",
                )
                assertEquals(
                    0,
                    run.probePathsOpened,
                    "no migration was recorded but ${run.probePathsOpened} probe path(s) were opened",
                )
            }
        }

    /**
     * **Where the margin actually is**, asserted rather than written down — the "holds" half.
     *
     * [SILENT_PATH_EXPIRY_THRESHOLD] is 4 because Chromium uses 4, and the honest question is how much
     * room that leaves. Measured here: the guard holds at 4 **and at 3**.
     *
     * ⚠️ At 2 the scenario stops *completing* — it dies on a 1m virtual-time `withTimeout` inside the
     * run rather than on the migration assertion — so "the boundary is at 2" is NOT asserted here. That
     * distinction is the point: a test that went red at 2 for an uncharacterised reason would read as
     * "the margin is one step" while actually proving something else. One step of margin below the
     * shipped value is what is measured and what is claimed.
     *
     * Two things this buys that a comment could not:
     *  - the sweep that established it ran on one host by editing a `const val`; this runs on every
     *    backend the shared suite runs on, each linking its own `libquiche`, so the boundary is
     *    checked against quiche's real loss-detection accounting rather than one platform's;
     *  - it is self-mutation-proving. A change that moves the boundary — in the predicate, in the
     *    reset, or in quiche's own `total_pto_count` semantics — fails this test instead of
     *    silently leaving the shipped value with less margin than the number implies.
     *
     * ⚠️ The #385 blip guard is NOT the one that discriminates here: it passes at 4, 3, 2 and 1,
     * because a 1.02s excursion is blocked by the time floor, not by the count. Reading a green
     * [aBlipTheLengthOfThe385ExcursionCostsNoMigration] as protection against a bad count is the
     * mistake these tests exist to prevent.
     *
     * ⚠️ One threshold per test body on purpose: two [withMigrationSim] runs in a single [runTest]
     * leave the first run's driver loops on the test scheduler, and the second run's establishment
     * budget then expires in virtual time before it can connect.
     *
     * ⚠️ Only one threshold is exercised here. A test at the shipped value would be a second run of
     * [separateBlipsDoNotAccumulateIntoAMigration] with identical seed, options and impairment — the
     * most expensive scenario in this file, re-run per backend for no new information — so the margin
     * is measured at the step below and nowhere else.
     */
    @Test
    fun separateBlipsDoNotAccumulateOneExpiryBelowTheShippedThreshold() = assertBlipMarginHolds(SILENT_PATH_EXPIRY_THRESHOLD - 1)

    private fun assertBlipMarginHolds(expiries: Long) =
        runTest {
            wrapTestBody {
                val run = blipRoundsUnder(SilenceThreshold(expiries, SILENT_PATH_MINIMUM_SILENCE))
                assertEquals(
                    BLIP_ROUNDS,
                    run.attemptsPerRound.size,
                    "the run did not complete every round, so a migration count read from it means nothing",
                )
                // Per round, not just the last: a migration in round 0 should name round 0, rather than
                // surfacing two rounds later as an echo that never came back.
                run.attemptsPerRound.forEachIndexed { round, attempts ->
                    assertEquals(
                        0,
                        attempts,
                        "at a threshold of $expiries expiries, ${round + 1} separate stall(s) — each under " +
                            "the threshold on its own — bought a migration, so the shipped " +
                            "$SILENT_PATH_EXPIRY_THRESHOLD has less margin than these tests assume",
                    )
                }
                // The same two premises the shipped-threshold guard checks. Without them a run that
                // never went dark, or that opened a probe path without recording an attempt, passes.
                assertTrue(
                    run.datagramsSwallowed >= BLIP_MIN_SWALLOWED * BLIP_ROUNDS,
                    "only ${run.datagramsSwallowed} datagram(s) were swallowed across $BLIP_ROUNDS stalls, " +
                        "so the data plane was never meaningfully dark and this margin is decorative",
                )
                assertEquals(
                    0,
                    run.probePathsOpened,
                    "no migration was recorded but ${run.probePathsOpened} probe path(s) were opened",
                )
            }
        }

    /** How a dark path's episode ended for [aPathThatGoesDarkUnderAWritingApplicationIsDeclaredSilentWithinThePatience]. */
    private sealed interface Detection {
        /** The reactor started probing [after] the path went dark. */
        data class Probed(
            val after: Duration,
        ) : Detection {
            override fun toString() = "probed $after after it went dark"
        }

        /** The connection closed without the path ever being declared silent. */
        data object ClosedFirst : Detection {
            override fun toString() = "never probed before the connection closed"
        }
    }

    private companion object {
        /** Payload size per write — big enough that a burst becomes many datagrams on the wire. */
        const val CHUNK_BYTES = 1000

        /** The link the connection is established on, and the one it hands off to. Ids are arbitrary. */
        val WIFI = NetworkId.Link(NetworkKind.Wifi, 1L)
        val CELLULAR = NetworkId.Link(NetworkKind.Cellular, 2L)

        /** A second Wi-Fi network — a different link from both [WIFI] and [CELLULAR]. */
        val OTHER_WIFI = NetworkId.Link(NetworkKind.Wifi, 3L)

        /** Dead-link attempts before the standby link attaches, in the backoff-cutting scenario. */
        const val FAILED_BEFORE_ATTACH = 3

        /**
         * The reactor's backoff after its third failed attempt (250ms doubling). A standby link that
         * attaches inside it must not wait it out.
         */
        val BACKOFF_AFTER_THIRD_ATTEMPT = 1.seconds

        /**
         * How long the real connection survived on its dead path before `IdleTimeout` killed it, on the
         * 2026-08-23 walk (30s, RFC 9000 §10.1). Used as the sim's idle timeout so "did the reactor
         * recover in time" is decided by the deadline the field actually gave us rather than by a number
         * chosen to make a test pass — the same constant, and the same reasoning, as
         * `AutoMigrationReactorTests.idleTimeoutInTheField`.
         */
        val IDLE_TIMEOUT_IN_THE_FIELD = 30.seconds

        /**
         * How long the 71h Android walk's `ConnectivityManager` kept calling a dead Wi-Fi link
         * `validated=true` before the reactor was handed anything: 11.5s, 11.5s and 11.9s across the
         * three real handoffs, 17 failed echoes in each. The smallest of those is the bound
         * [aDeadPathReHomesWhileTheMonitorStillCallsTheLinkHealthy] holds the data-plane trigger to —
         * a trigger no faster than the signal it exists to beat would be no fix at all. It is a
         * measurement, never a threshold: nothing in production reads it.
         */
        val ANDROID_MONITOR_LAG = 11_500.milliseconds

        /** The 2026-09-24 walk probe's echo cadence (`echoIntervalMs=250`). */
        val WALK_ECHO_INTERVAL = 250.milliseconds

        /** A write cadence under the sim path's PTO, so no expiry fires at all: the walk's v4 connection 2. */
        val FAST_WRITE_INTERVAL = 100.milliseconds

        /** Room for the reactor to put its probe on the wire once silence is declared. */
        val UNANSWERED_WRITE_SLACK = 500.milliseconds

        /**
         * The cellular excursion #385 recorded on a real iPhone ride trace — Wi-Fi → cellular → Wi-Fi
         * in 1.02s — replayed here as what it looks like from *inside* the connection: the active path
         * stops carrying anything for 1.02s and then comes back. Stated to three digits because it is
         * quoted, not chosen.
         */
        val BLIP_385 = 1_020.milliseconds

        /**
         * Datagrams the blip must have swallowed for
         * [aBlipTheLengthOfThe385ExcursionCostsNoMigration] to be measuring anything. Measured on this
         * rig at 4 (the write in flight plus two PTO probes); three leaves room for the seeded
         * sequence to shift without the guard turning into a green that means nothing.
         */
        const val BLIP_MIN_SWALLOWED = 3

        /**
         * Stalls [separateBlipsDoNotAccumulateIntoAMigration] runs back to back, and the unanswered PTO
         * fires each one is held open for.
         *
         * Two fires is under the shipped threshold on its own — the #385 guard next door pins that from
         * the other side, measuring that the threshold has to be dropped to two before one stall of the
         * recorded length trips it. `BLIP_ROUNDS × DARK_ROUND_PTOS` is comfortably over it, which is the
         * whole arithmetic: a tally that never resets crosses inside this test and a run that resets
         * never does.
         */
        const val BLIP_ROUNDS = 4
        const val DARK_ROUND_PTOS = 2L

        /**
         * One: when a migration completes the driver retires the old CID on the new path; the peer's
         * ACK of that retirement, one round trip later, is the path's first RTT sample and every later
         * path inherits it, so an unsampled active path exists only inside that first round trip.
         */
        const val PRIOR_MIGRATIONS = 1

        /**
         * Round trip above which a path is certainly **un-sampled** — quiche is reporting RFC 9002
         * §5.1's initial `kInitialRtt = 333ms` rather than anything it measured.
         *
         * 300ms, so the check is against the initial value and not against a threshold: the sim's
         * slowest arm is a 240ms round trip and its probe paths are 70ms, so nothing this suite runs
         * can reach here by measuring honestly.
         */
        val UNSAMPLED_RTT_FLOOR = 300.milliseconds

        /**
         * ⚠️ **Ceiling on one stall, and the reason this test stopped meaning what it said.**
         *
         * The rounds used to be sized by [DARK_ROUND_PTOS] alone, with no bound on how long two expiries
         * were allowed to take. They compound: a stall inflates the path's own RTT estimate (`srtt=540ms
         * rttvar=966ms` measured on a path that had been dark), so the next stall's two expiries cost
         * four times as much. Measured, the three "blips" this file asserted must not migrate were
         * **750ms, 4.8s and 20.3s** — and it was the 20.3s one that made the guard read as "a twenty
         * second blackout must not re-home", which is #574 itself, defended by a test named for #385.
         *
         * With this bound and [BLIP_SETTLE_ECHOES] between rounds, every stall is 700–750ms — shorter
         * than [BLIP_385], the longest excursion actually on record — and the tally still crosses.
         */
        val BLIP_ROUND_LIMIT = 2.seconds

        /**
         * Successful round trips between stalls, which is what walks the inflated RTT estimate back
         * down. Not padding: at four of them the later rounds contribute no expiries at all
         * (`fires=[2, 1, 1, 0]`) and the tally never reaches the threshold, so the test passes while
         * proving nothing. At twenty it is `fires=[2, 2, 2, 2]`.
         */
        const val BLIP_SETTLE_ECHOES = 20

        /**
         * Bound on one stall, so a round that can never see its fires fails loudly instead of hanging
         * the suite. Generous against a PTO that has already backed off to seconds, and virtual.
         */
        val DARK_ROUND_LIMIT = 60.seconds

        /**
         * One-way latencies [aBlipTheLengthOfThe385ExcursionCostsNoMigration] sweeps. The low end is
         * where a count-only threshold re-opens #385 (2/10/20ms bought a migration, 40/60/120ms did
         * not) and is also where #385's own trace lived — an iPhone on Wi-Fi, 10–30ms to a CDN edge.
         * Virtual time, so the whole sweep is free.
         */
        val BLIP_LATENCIES = listOf(2, 10, 20, 40, 60, 120).map { it.milliseconds }

        /**
         * Loss rates [aLossyPathIsNotASilentOne] sweeps on a path that is otherwise fine. 30% is well
         * past anything a usable link does; the point is that the expiry counter runs at all under
         * loss, not that the link is realistic.
         */
        val LOSSY_RATES = listOf(0.10, 0.20, 0.30)

        /** Echoes per loss rate — enough round trips that the expiry counter has time to run ahead. */
        const val LOSSY_ECHOES = 12

        /** Idle gap between lossy echoes, so the loss timer is armed and expiring rather than idle. */
        val LOSSY_GAP = 500.milliseconds

        /**
         * Read/write bound for [separateBlipsDoNotAccumulateIntoAMigration]. Each stall leaves the echo
         * waiting on the *next* PTO after the heal, and that gap has itself been doubling all test, so
         * this is deliberately far past any of them. Virtual.
         */
        val STALL_ECHO_TIMEOUT = 2.minutes

        /**
         * Quiet time after the blip has healed, before the "no migration" verdict is taken. Comfortably
         * past the fourth consecutive PTO of the *original* silence (3.675s from the last packet
         * received, at this rig's 120ms round trip), so a trigger that merely armed late would still be
         * caught. Virtual, so it is free.
         */
        val BLIP_SETTLE = 10.seconds

        /**
         * Probes swallowed before the link comes good in [aLostProbeIsRetriedUntilTheConnectionRehomes].
         * One is the measured field case; two is one more than that. Each takes a fresh socket only while
         * the pool can spare one — see that test's KDoc.
         */
        const val LOST_PROBES = 2

        /**
         * The MTU of #637's link, in bytes of UDP payload: above RFC 9000 §8.2.1's 1200-byte floor and
         * below [QuicheDriver.MAX_DATAGRAM_SIZE], which is the whole window the defect lives in. 1280 is
         * IPv6's own minimum link MTU, so the number is one a real leg actually has.
         */
        const val CONSTRAINED_LINK_MTU = 1280

        /**
         * What [aValidatedPathIsNotHeldToThePathValidationFloor] pushes. Deliberately far larger than a
         * datagram per write: the measurement is "how big does the sender make a datagram when it has
         * plenty to put in one", and a stream fed 1 KB at a time is application-limited, so quiche
         * empties the queue into short datagrams and the largest one seen is the handshake Initial's own
         * 1200-byte padding rather than anything the send window decided.
         */
        const val BULK_CHUNK_BYTES = 32 * 1024
        const val BULK_CHUNKS = 8

        /** Long enough that a bounded backoff has certainly finished; virtual, so it is free. */
        val GIVE_UP_WINDOW = 60.seconds

        /**
         * Settle after an abandoned probe, for the RETIRE_CONNECTION_ID -> NEW_CONNECTION_ID round
         * trip on a healthy path. Generous on purpose — it is virtual time, and a tight value would
         * let #459 read as a race rather than the permanent loss it is.
         */
        val REPLENISH_SETTLE = 10.seconds

        /**
         * One-way path latencies [anAbandonedProbeGivesItsConnectionIdBackToThePool] sweeps. Zero is
         * the loopback/simulator case that a timing-only fix passes; 60ms and up is where quiche's
         * probe-loss PTO beats the disarming ACK and the leak reappears. 125ms one-way is an ordinary
         * mobile round trip. Virtual time, so the whole sweep is free.
         */
        val POOL_RECOVERY_LATENCIES = listOf(0, 30, 60, 125).map { it.milliseconds }

        /**
         * How much past its own idle timeout each arm of
         * [aDeadPathHandoffIsEndedByTheConnectionsOwnDeadline] waits for the connection to close.
         * Generous, and virtual: a tight bound would turn "did it die" into a race.
         */
        const val DEADLINE_SLACK = 4

        /** The two idle windows [aDeadPathHandoffIsEndedByTheConnectionsOwnDeadline] compares. */
        val SHORT_IDLE_WINDOW = 10.seconds
        val LONG_IDLE_WINDOW = 60.seconds

        /**
         * Comfortably inside [SHORT_IDLE_WINDOW], so both arms of that test keep their connection alive
         * across a scenario in which the application sends nothing at all.
         */
        val KEEPALIVE = 3.seconds

        /**
         * Options for [aHandoffOntoALinkThatNeverAnswersBacksOffWithoutGivingUp].
         *
         * The two quiet windows in that test add up to longer than any real idle timeout, and a
         * blackholed probe brings back nothing to restart the timer, so the idle deadline is moved out
         * of the way deliberately — surviving a *field* deadline is
         * [aLostProbeIsRetriedUntilTheConnectionRehomes]'s assertion, and that test is about where the
         * asking stops.
         */
        fun quietHandoffOptions(monitor: SimNetworkMonitor) =
            migrationSimOptions(
                idleTimeout = 5.minutes,
                migration = MigrationPolicy.Automatic,
                networkMonitor = NetworkMonitorSource.Supplied(monitor),
            )

        /**
         * Spare destination CIDs a connection can hold at once: [QuicOptions.activeConnectionIdLimit]
         * minus the one in use. Read from the **shipped default** rather than pinned, unlike the
         * per-platform suites — this sim runs on virtual time, so exercising whatever the library
         * actually ships costs nothing and one fewer constant can drift.
         */
        val SPARE_POOL: Long = QuicOptions(alpnProtocols = listOf("migsim")).activeConnectionIdLimit - 1

        /** One past exhaustion — the same reasoning as the real suite's constant of the same name. */
        val FAILED_ATTEMPTS = (SPARE_POOL + 1).toInt()

        /** Bounded retries for the RETIRE -> NEW_CONNECTION_ID round trip, as the real suite does. */
        const val REPLENISH_RETRIES = 40

        /**
         * The `active_connection_id_limit` the field echo server advertised on the 2026-09-10 and
         * 2026-09-12 iPhone walks (read from the walk qlog's remote `parameters_set`): the old default,
         * which caps the client's spare pool at [WALK_PEER_SPARES] regardless of the client's own limit.
         */
        const val WALK_PEER_CID_LIMIT = 4L

        /** Spares a client holds against [WALK_PEER_CID_LIMIT]: the limit minus the id in use — one probe each. */
        const val WALK_PEER_SPARES = WALK_PEER_CID_LIMIT - 1

        /**
         * How long after the handoff the walk's cellular link starts answering: past the three probes
         * the pool paid for (the last failed at t+9.8s of the handoff on connection 7) and well inside
         * the 30s idle window — the next connection reached the same server over that link at t+30s.
         */
        val WALK_LINK_ATTACH = 10.seconds

        /**
         * How long the 2026-09-21 burst's link held probes before releasing them: its longest-held
         * probe was in flight for 16.1s.
         */
        val LINK_HOLD = 16.seconds

        /** How long the 2026-09-25 walk's cellular uplink held packets, toward the short end of its 12–47 s. */
        val UPLINK_HOLD = 20.seconds

        /** What the server pushes mid-hold in [aSendStalledBehindAHeldUplinkLeavesTheActivePathReceiving]. */
        const val PUSHED = "pushed"

        /** The walk's server side: same ALPN and idle window as the client, the old CID limit. */
        fun walkPeerOptions(): QuicOptions =
            QuicOptions(
                alpnProtocols = listOf("migsim"),
                verifyPeer = false,
                idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                activeConnectionIdLimit = WALK_PEER_CID_LIMIT,
            )
    }
}
