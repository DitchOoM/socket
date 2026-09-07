package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
     * `recv()` refill relinking spares into paths that have none. Measured here: after one abandoned
     * probe the count sits flat at 2 while the audit shows the server *did* receive the retirement and
     * issue a replacement (`retiredScidsSeen` 1, `newScid` 4). A test asserting "the count went back
     * up" would read that flat 2 as a failure — and could read a wrong fix as a success.
     *
     * So the assertion is what #447 actually changed, and what is unambiguous: **the abandoned path
     * retired the destination CID it was holding.** Pre-#447 the driver wrote that sequence number into
     * native scratch and never read it, so its abandon exit had no value to forget and made no retire
     * call at all — mutated back, this goes red with an empty `retireCalls`.
     *
     * The scenario costs 0ms of wall clock; the ~3s abandon budget is virtual.
     */
    @Test
    fun anAbandonedProbeRetiresTheConnectionIdItHeld() =
        runTest {
            wrapTestBody {
                withMigrationSim(
                    simEnv(),
                    seed = 77_001L,
                    probeImpairment = { PathImpairment(blackhole = true) },
                ) {
                    awaitSpareDcids()
                    assertTrue(clientAvailableDcids() > 0, "the peer never issued a spare CID — nothing could be leaked")

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
                        1,
                        clientAudit.retireCalls.size,
                        "an abandoned probe must retire the destination CID quiche linked to its path, " +
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
                    probeImpairment = { PathImpairment(blackhole = blackholeProbes) },
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
     * ⚠️ [LOST_PROBES] is bounded by the spare CID pool, not chosen for effect: the old path is dead,
     * so each abandoned probe's RETIRE_CONNECTION_ID never reaches the peer and no replacement ever
     * comes back. The pool is [SPARE_POOL], and the scenario states that precondition out loud by
     * waiting for it below. Every §8.2.4 abandon budget here is virtual, so the whole thing costs 0ms
     * of wall clock.
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
                    probeImpairment = { index -> PathImpairment(blackhole = index <= LOST_PROBES) },
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
                        // Stated as a precondition rather than assumed: this scenario spends one spare
                        // destination CID per probe and gets none of them back, because the path that
                        // would carry RETIRE_CONNECTION_ID is about to die.
                        awaitSpareDcids(count = (LOST_PROBES + 1).toLong())
                        assertEquals(0, client.attempts.size, "nothing may migrate before the handoff")

                        // --- the handoff, and the last input this test ever supplies ---
                        pipe.impair(pipe.paths().first().local, PathImpairment(blackhole = true))
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
                    probeImpairment = { PathImpairment(blackhole = true) },
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
     * **Every attempt in one handoff's budget must reach the network** — the property
     * [QuicOptions.activeConnectionIdLimit]'s default exists to hold.
     *
     * The reactor's retry budget and the spare connection id pool are two different ceilings on the
     * same loop, and only one of them is about the network. Past the pool, `QuicheDriver.handleMigrate`
     * answers [MigrationResult.Unmoved.Failed.NoSpareConnectionId] *before* `openPath` — a truthful
     * answer that opens no socket, sends no PATH_CHALLENGE and gives the handoff no new chance. So a
     * pool smaller than the budget does not shorten the loop; it **hollows it out**, and the shortfall
     * is invisible from the outside because the attempt count is unchanged.
     *
     * Measured on this rig with the old default of 4: six attempts, three probes, the reactor giving
     * up at 16.75s with a third of the 30s idle window unspent. At the shipped default the two lines
     * below are the same number.
     *
     * The old path must be dead for this to mean anything. On a live path each abandoned probe's
     * `RETIRE_CONNECTION_ID` reaches the peer and is replaced, so the pool never empties and this
     * would pass at any limit down to the RFC minimum of two.
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
                            // The field's own deadline, deliberately: the invariant below only holds
                            // where the idle window allows fewer attempts than the pool can supply, and
                            // 30s is both QuicOptions' default and what the #453 connection actually had.
                            idleTimeout = IDLE_TIMEOUT_IN_THE_FIELD,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    probeImpairment = { PathImpairment(blackhole = true) },
                ) {
                    awaitSpareDcids(count = SPARE_POOL)
                    pipe.impair(pipe.paths().first().local, PathImpairment(blackhole = true))
                    monitor.setNetworkId(CELLULAR)
                    delay(GIVE_UP_WINDOW)

                    val refused = client.attempts.filterIsInstance<MigrationResult.Unmoved.Failed.NoSpareConnectionId>()
                    // The last attempt of a dead-path handoff is the one that discovers the connection
                    // has idled out; it answers Impossible and opens no path, which is the loop ENDING
                    // rather than a retry that failed to reach anywhere. Everything before it must have
                    // become a real probe.
                    val reachedTheDriver = client.attempts.filter { it !is MigrationResult.Unmoved.Impossible }
                    assertEquals(
                        reachedTheDriver.size,
                        clientPaths().size,
                        "the handoff made ${reachedTheDriver.size} attempt(s) against a live connection " +
                            "but opened only ${clientPaths().size} probe path(s): ${refused.size} were " +
                            "refused for want of a spare connection id before a socket was opened, so " +
                            "that much of the retry never reached the network. The spare pool is " +
                            "$SPARE_POOL (activeConnectionIdLimit - 1). Attempts: ${client.attempts}",
                    )
                    assertTrue(
                        refused.isEmpty(),
                        "${refused.size} attempt(s) were refused for want of a spare connection id on a " +
                            "handoff the pool of $SPARE_POOL should have covered: ${client.attempts}",
                    )
                    assertTrue(
                        client.attempts.size >= 2,
                        "no retry happened at all, so this test is measuring nothing: ${client.attempts}",
                    )
                    assertTrue(
                        client.attempts.size.toLong() <= SPARE_POOL,
                        "the ${IDLE_TIMEOUT_IN_THE_FIELD} idle window allowed ${client.attempts.size} " +
                            "attempts but the pool only holds $SPARE_POOL spare connection ids, so the " +
                            "two ceilings have crossed and the surplus can never reach the network",
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
                    probeImpairment = { PathImpairment(blackhole = true) },
                ) {
                    awaitSpareDcids()
                    // The old path dies with the handoff, as it does in the field — otherwise the
                    // connection survives, nothing ends the loop, and there is no deadline to measure.
                    pipe.impair(pipe.paths().first().local, PathImpairment(blackhole = true))
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
     * **#459 — an abandoned probe must give its connection id back to the pool.**
     *
     * #447 fixed "the abandoned path never retires its connection id". This is the other half, and it
     * is invisible from that one: we *do* retire, the peer *does* send a replacement, and quiche links
     * the replacement **straight back into the dead probe path**, which pins it un-evictable
     * (`PathMap::unused()` is `!active() && active_dcid_seq.is_none()`) and holding a spare forever.
     *
     * So every failed handoff costs a spare permanently, on a connection where nothing is wrong. After
     * [SPARE_POOL] of them, `migrate()` answers
     * [MigrationResult.Unmoved.Failed.NoSpareConnectionId] for the rest of the connection's life — a
     * phone that fails a handoff a few times has silently lost active migration, and the only symptom
     * is that a later, perfectly good handoff does not happen.
     *
     * ## Why the original path is HEALTHY here
     * That is the whole point. On a dead path there is nothing to argue about — the retirement cannot
     * cross and the pool is *expected* to drain, which is what
     * [everyRetryInTheBudgetReachesTheNetwork] is about. Here the retirement lands, the peer replies,
     * and the pool still does not recover. Anything less than a healthy path would leave the failure
     * explainable by the network.
     *
     * ## Why it drives migrate() by hand
     * [MigrationPolicy.Manual], deliberately: the automatic reactor's retry budget is smaller than the
     * pool, so it stops one attempt *before* exhaustion and cannot observe this at all. The budget was
     * hiding the defect, which is why it cannot be removed until this is fixed (#459, #453).
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
                        probeImpairment = { PathImpairment(blackhole = true) },
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
                            SPARE_POOL,
                            clientAvailableDcids(),
                            "at a one-way path latency of $latency: after ${outcomes.size} abandoned probes " +
                                "and $REPLENISH_SETTLE of settle each, the spare pool is " +
                                "${clientAvailableDcids()} instead of $SPARE_POOL. Trajectory: $trajectory",
                        )
                    }
                }
            }
        }

    /**
     * **#574 — the path is dead, the platform has not noticed, and the connection re-homes anyway.**
     *
     * Every trigger this reactor had was a *control-plane* one: [NetworkMonitor.state]. On the 71h
     * Android walk (2026-09-06) that signal lagged reality by a measured 11.5s / 11.5s / 11.9s on the
     * three real Wi-Fi→cellular handoffs, with 17 consecutive failed echoes in each window and the
     * heartbeat immediately before each outage still reporting `net=wifi validated=true`. The
     * migration itself then took 338ms–1954ms. So a ~12.5s user-visible outage was ~12s of *waiting
     * to be told* and under 2s of QUIC. iOS's `NWPathMonitor` reports the same handoff in ~1s, which
     * is the whole of the 10× platform asymmetry.
     *
     * This scenario is that field condition with nothing scripted: the primary path stops carrying
     * anything, and [SimNetworkMonitor] is **never touched again** — its [NetworkId] is the same
     * `WIFI` it was established on, from the first line to the last. That constancy is the test.
     * Under the old reactor the merged trigger stream has exactly one emission for the whole run (the
     * connect-time baseline), so nothing can call `migrate()` and the connection idles out on a path
     * that is provably carrying nothing.
     *
     * ## What is asserted, and why it is not a counter
     * The echo is the assertion, exactly as in [aLostProbeIsRetriedUntilTheConnectionRehomes]: with
     * the primary blackholed there is no route to the server except a successful migration, so "did
     * the driver notice" and "did the connection survive" are the same question. The one number in it,
     * [ANDROID_MONITOR_LAG], is the field's own — the time Android took merely to *start* probing — so
     * the bound is a measurement rather than a threshold chosen to fit, and it is held against the same
     * edge the walk reported (`PATH Probing`) rather than a proxy. The monitor's identity is re-read at
     * the end because a future change that quietly makes this a link-change test would otherwise still
     * pass.
     *
     * ⚠️ [ANDROID_MONITOR_LAG] alone is far too loose to be the only bound: it is 3× the detection this
     * ships, so an entire doubling of the PTO backoff can be lost inside it with the suite green — and
     * one was, when the handshake-time baseline in `QuicheDriver.updateState` was missing (5 expiries
     * and 7.635s instead of 4 and 3.695s). So the tighter assertion is in **expiries**, against
     * [SILENT_PATH_EXPIRY_THRESHOLD] itself: at this rig's 120ms round trip the count is the binding
     * half of the conjunction, so the trigger must fire on exactly the Nth unanswered expiry and never
     * the N+1th. That assertion moves if the constant is deliberately retuned, which is correct — the
     * constant is the specification — and does not move for any change to the *sampler*, which is what
     * it is guarding.
     *
     * ⚠️ The probe path carries a real 35ms latency, not the sim's zero default: a CID/path fix that
     * only works at RTT≈0 has shipped here before (#445, #459), and the asymmetry — new path faster
     * than the one being left — is also the #445 overtake window.
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
                        pipe.impair(primary.local, PathImpairment(blackhole = true))

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
                            pipe.impair(primary.local, PathImpairment(blackhole = true))
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
                repeat(BLIP_ROUNDS) { round ->
                    write("blip$round")
                    val firesBefore = ptoFires()
                    pipe.impair(primary.local, PathImpairment(blackhole = true))
                    withTimeout(DARK_ROUND_LIMIT) {
                        while (ptoFires() - firesBefore < DARK_ROUND_PTOS) delay(50.milliseconds)
                    }
                    pipe.impair(primary.local, PathImpairment(latency = DEFAULT_PATH_LATENCY))
                    assertEquals(
                        "blip$round",
                        read(),
                        "round $round: the path healed but the echo never came back, so the " +
                            "rounds after it would prove nothing",
                    )
                    attemptsPerRound += client.attempts.size
                }
                val run = BlipRun(attemptsPerRound.toList(), clientPaths().size, primary.stats.blackholed)
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
                            "connection reaches the threshold eventually, on no evidence at all",
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
     *    reset, or in quiche's own `total_pto_count` semantics — fails one of these three tests
     *    instead of silently leaving 4 with less margin than the number implies.
     *
     * ⚠️ The #385 blip guard is NOT the one that discriminates here: it passes at 4, 3, 2 and 1,
     * because a 1.02s excursion is blocked by the time floor, not by the count. Reading a green
     * [aBlipTheLengthOfThe385ExcursionCostsNoMigration] as protection against a bad count is the
     * mistake these tests exist to prevent.
     *
     * ⚠️ One threshold per test body on purpose: two [withMigrationSim] runs in a single [runTest]
     * leave the first run's driver loops on the test scheduler, and the second run's establishment
     * budget then expires in virtual time before it can connect.
     */
    @Test
    fun separateBlipsDoNotAccumulateAtTheShippedThreshold() = assertBlipMarginHolds(SILENT_PATH_EXPIRY_THRESHOLD)

    /** The step of margin below the shipped threshold — see [separateBlipsDoNotAccumulateAtTheShippedThreshold]. */
    @Test
    fun separateBlipsDoNotAccumulateOneExpiryBelowTheShippedThreshold() = assertBlipMarginHolds(SILENT_PATH_EXPIRY_THRESHOLD - 1)

    private fun assertBlipMarginHolds(expiries: Long) =
        runTest(timeout = 10.minutes) {
            wrapTestBody {
                val run = blipRoundsUnder(SilenceThreshold(expiries, SILENT_PATH_MINIMUM_SILENCE))
                assertEquals(
                    0,
                    run.attemptsPerRound.lastOrNull() ?: 0,
                    "at a threshold of $expiries expiries the separate-blip run bought a migration, so the " +
                        "shipped $SILENT_PATH_EXPIRY_THRESHOLD has less margin than these tests assume",
                )
            }
        }

    private companion object {
        /** Payload size per write — big enough that a burst becomes many datagrams on the wire. */
        const val CHUNK_BYTES = 1000

        /** The link the connection is established on, and the one it hands off to. Ids are arbitrary. */
        val WIFI = NetworkId.Link(NetworkKind.Wifi, 1L)
        val CELLULAR = NetworkId.Link(NetworkKind.Cellular, 2L)

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
        const val BLIP_ROUNDS = 3
        const val DARK_ROUND_PTOS = 2L

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
         * One is the measured field case; two is one more than that and still inside the spare CID pool
         * (limit 4, minus the one in use, and nothing is replenished while the old path is dark), which
         * is what bounds it — see that test's KDoc.
         */
        const val LOST_PROBES = 2

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
    }
}
