package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.delay
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
class MigrationSimTests {
    @Test
    fun aClientMigratesToAFreshPathUnderVirtualTime() =
        runTest {
            try {
                withMigrationSim(
                    testScope = this,
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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
            try {
                withMigrationSim(
                    testScope = this,
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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
            try {
                withMigrationSim(
                    testScope = this,
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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
            try {
                withMigrationSim(
                    testScope = this,
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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
            try {
                withMigrationSim(
                    testScope = this,
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
            }
        }

    /**
     * **The other half of the #453 contract: retrying is bounded, and the old path keeps working.**
     *
     * A retry loop that answers "the probe was lost" with "ask again" has to say where it stops, or
     * #453 is traded for #385 by another route — a reactor that spends the connection's whole life
     * probing a link that will never answer. Here the handoff is onto a link that is simply *gone*:
     * every probe path is a blackhole, forever, and the platform never reports anything again.
     *
     * Two things must hold, and they are in tension:
     *  - the reactor stops (bounded by [wireAutoMigration]'s attempt budget, which is itself sized
     *    against the spare CID pool — past the pool quiche refuses before opening a socket, so those
     *    attempts are free);
     *  - the connection **survives**, on the path it never left. A failed migration must not cost the
     *    caller anything, which is the property `MigrationResult.Unmoved` exists to state.
     *
     * Unlike [aLostProbeIsRetriedUntilTheConnectionRehomes] the original path stays healthy here — the
     * scenario is "the new link is not usable", not "the old link died", and conflating the two would
     * make "did it survive" untestable.
     */
    @Test
    fun aHandoffOntoALinkThatNeverAnswersStopsAsking() =
        runTest {
            val monitor = SimNetworkMonitor.on(WIFI)
            try {
                withMigrationSim(
                    testScope = this,
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
                        // Far past any bounded backoff: if the reactor is going to stop, it has stopped.
                        delay(GIVE_UP_WINDOW)
                        val settled = client.attempts.size

                        // Exactly the budget, not merely within it. The link never answers and never
                        // will, no other link appears, and no leaf here is the one that stops early
                        // (EndpointNotSelectable) — so a reactor honouring its derivation spends the
                        // budget to the last attempt and then stops. Asserting a *range* would pass
                        // against a reactor that ignored the derivation entirely and used some smaller
                        // constant, which is precisely the regression worth catching.
                        val budget = migrationAttemptBudget(quietHandoffOptions(monitor))
                        assertEquals(
                            budget,
                            settled,
                            "one handoff onto a dead link made $settled migration attempt(s) in " +
                                "$GIVE_UP_WINDOW against a derived budget of $budget. Fewer is a reactor " +
                                "giving up while its own deadline still had room (one attempt is #453 " +
                                "itself); more is one that outran the budget and kept probing a link " +
                                "that will never answer. Attempts: ${client.attempts}",
                        )
                        assertTrue(
                            client.attempts.none { it is MigrationResult.Succeeded },
                            "every probe path is a blackhole, so no attempt could have succeeded — the " +
                                "impairment is not reaching the paths: ${client.attempts}",
                        )

                        delay(GIVE_UP_WINDOW)
                        assertEquals(
                            settled,
                            client.attempts.size,
                            "the reactor was still asking $GIVE_UP_WINDOW later, with no new information " +
                                "of any kind — that is a spin, not a backoff: ${client.attempts}",
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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
            try {
                withMigrationSim(
                    testScope = this,
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
                    assertEquals(
                        client.attempts.size,
                        clientPaths().size,
                        "the handoff made ${client.attempts.size} attempt(s) but opened only " +
                            "${clientPaths().size} probe path(s): ${refused.size} were refused for want of " +
                            "a spare connection id before a socket was opened, so that much of the retry " +
                            "budget never reached the network. The spare pool is $SPARE_POOL " +
                            "(activeConnectionIdLimit - 1) and it must exceed the reactor's attempt " +
                            "budget. Attempts: ${client.attempts}",
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
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
            }
        }

    /**
     * **The retry budget really is the idle window's, measured end to end rather than by asking the
     * function that computes it.**
     *
     * [aHandoffOntoALinkThatNeverAnswersStopsAsking] asserts the reactor spends exactly
     * [migrationAttemptBudget]'s answer — which pins them together but cannot tell whether that answer
     * is derived from anything, since the test calls the same function. This one never mentions the
     * budget: it runs the identical dead-link handoff under two [QuicOptions.idleTimeout]s and requires
     * the shorter window to buy strictly fewer attempts. A reactor back on a fixed constant makes the
     * same number of attempts in both arms and goes red here.
     *
     * Both arms carry a keepalive well inside their own idle timeout, so the connection survives the
     * whole scenario and the only thing that can stop the loop is the budget. Without it the short arm
     * would idle out mid-loop and "fewer attempts" would be measuring the connection's death instead of
     * the policy — the same number, for the wrong reason.
     */
    @Test
    fun aShorterIdleWindowBuysFewerRetries() =
        runTest {
            suspend fun attemptsUnder(idleTimeout: kotlin.time.Duration): Int {
                val monitor = SimNetworkMonitor.on(WIFI)
                var attempts = -1
                withMigrationSim(
                    testScope = this,
                    seed = 45_304L,
                    quicOptions =
                        migrationSimOptions(
                            idleTimeout = idleTimeout,
                            keepAliveInterval = KEEPALIVE,
                            migration = MigrationPolicy.Automatic,
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                        ),
                    probeImpairment = { PathImpairment(blackhole = true) },
                ) {
                    awaitSpareDcids()
                    monitor.setNetworkId(CELLULAR)
                    delay(GIVE_UP_WINDOW)
                    assertIs<QuicConnectionState.Established>(
                        clientDriver.state.value,
                        "the connection died under a $idleTimeout idle timeout despite a $KEEPALIVE " +
                            "keepalive, so the attempt count below would be measuring its death",
                    )
                    attempts = client.attempts.size
                }
                return attempts
            }

            try {
                val short = attemptsUnder(SHORT_IDLE_WINDOW)
                val long = attemptsUnder(LONG_IDLE_WINDOW)
                assertTrue(
                    short < long,
                    "a $SHORT_IDLE_WINDOW idle window bought $short migration attempt(s) and a " +
                        "$LONG_IDLE_WINDOW window bought $long — the same or more, so the reactor's " +
                        "retry budget is not derived from the deadline that ends the connection. A " +
                        "caller who shortens idleTimeout is then probing a connection that is already " +
                        "gone, and one who lengthens it is paying for a window the reactor never uses",
                )
                assertTrue(short >= 2, "even the short window must allow a retry, or this is #453 again")
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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
            try {
                withMigrationSim(
                    testScope = this,
                    seed = 45_900L,
                    quicOptions = migrationSimOptions(idleTimeout = 10.minutes, keepAliveInterval = KEEPALIVE),
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
                        "after ${outcomes.size} abandoned probes and $REPLENISH_SETTLE of settle each, " +
                            "the spare pool is ${clientAvailableDcids()} instead of $SPARE_POOL. " +
                            "Trajectory: $trajectory",
                    )
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
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

        /** The two idle windows [aShorterIdleWindowBuysFewerRetries] compares. */
        val SHORT_IDLE_WINDOW = 10.seconds
        val LONG_IDLE_WINDOW = 60.seconds

        /**
         * Comfortably inside [SHORT_IDLE_WINDOW], so both arms of that test keep their connection alive
         * across a scenario in which the application sends nothing at all.
         */
        val KEEPALIVE = 3.seconds

        /**
         * Options for [aHandoffOntoALinkThatNeverAnswersStopsAsking].
         *
         * The two quiet windows in that test add up to longer than any real idle timeout, and a
         * blackholed probe brings back nothing to restart the timer, so the idle deadline is moved out
         * of the way deliberately — surviving a *field* deadline is
         * [aLostProbeIsRetriedUntilTheConnectionRehomes]'s assertion, and that test is about where the
         * asking stops. A factory rather than a value so the test can hand the identical options to
         * [migrationAttemptBudget] and assert against the reactor's own derivation instead of a literal
         * that would silently drift from it.
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
