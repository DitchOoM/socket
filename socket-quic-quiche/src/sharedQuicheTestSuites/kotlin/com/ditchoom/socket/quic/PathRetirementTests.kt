@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **A successful migration must retire the path it migrated from** (issue #395).
 *
 * Before this suite, the driver's `Validated` arm switched to the new path and walked away: the old
 * path's UDP socket, its reader coroutine, its `recv_info` and its pinned sockaddr all stayed live for
 * the connection's whole life, `paths` grew by one entry per migration, and — because
 * `teardownPath` opened with `if (entry.isPrimary) return` while `isPrimary` was a `val` — the
 * original path could *never* be released. On a 101-minute real-hardware handoff run (#393's field
 * log) the dead path's reader was still running at the end.
 *
 * These tests pin the retirement contract at the driver level with [StubQuicheApi]: the migrated-from
 * socket closes **at migration time** (not at `cleanup()`), its reader stops, the old path's DCID is
 * retired per RFC 9000 §9.5 with the *tracked* sequence number, egress follows the new path, a probe
 * that collides with the live path's endpoint is refused rather than silently orphaning the live
 * entry, and retired SCID capacity is re-issued instead of issued exactly once. The end-to-end proof
 * that quiche then keeps accepting migrations past `active_connection_id_limit` is
 * `QuicActiveMigrationTestSuite.theConnectionCanKeepMigratingPastTheConnectionIdLimit`.
 *
 * Everything runs on [runTest]'s single-threaded virtual scheduler with
 * `driverContext = EmptyCoroutineContext`, the `PathValidationTimeoutTests` discipline. That is not a
 * convenience: [StubQuicheApi]'s scripting seams (`pathEvents`, `connSendQueue`, …) are plain
 * collections, and the driver's startup `afterCommand` already drains path events — under a real
 * dispatcher a test-thread enqueue races that drain and the event is silently discarded (measured:
 * the RFC 8.2.4 abandon timer then answers `PathNotValidated` three seconds later, nondeterministically
 * per test body). On the test scheduler the driver only runs inside [runCurrent], so every enqueue
 * is sequenced and every "did NOT happen" assertion is meaningful. Virtual time also never advances
 * past the abandon budget, so no test here can pass by accidentally timing out.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, and this directory is
 * `srcDir`'d into both — so one copy of the suite runs on jvm/apple/linux *and* the Android device
 * lane, which ships the JNI backend these paths actually take in production. See DitchOoM/socket#390.
 * (The abandon-timer variants stay in `commonTest`'s `PathValidationTimeoutTests`: they need the
 * virtual [com.ditchoom.socket.quic.sim.SimClock], which lives there.)
 */
class PathRetirementTests {
    private val bufferFactory = BufferFactory.deterministic()

    /**
     * Fake pinned sockaddrs, never dereferenced — [StubQuicheApi] decodes them from its registry.
     * First-page addresses can never collide with a real allocation (see PathValidationTimeoutTests).
     */
    private val peerAddr = 0x1100L
    private val primaryAddr = 0x1200L
    private val primaryPort = 40000
    private val probePortBase = 41000

    /**
     * A [UdpChannel] double that reports the three lifecycle facts retirement is about: datagrams
     * egressed ([sendCount]), the socket closed ([closeCount]), and the reader loop actually ended
     * ([readerCancelled] — completed from the `finally` around the parked receive, so its completion
     * is the reader's own last act, not an inference).
     */
    private class RetirementChannel : UdpChannel {
        var sendCount = 0
            private set
        var closeCount = 0
            private set
        val readerCancelled = CompletableDeferred<Unit>()

        override suspend fun receive(buffer: PlatformBuffer): Int {
            try {
                awaitCancellation()
            } finally {
                readerCancelled.complete(Unit)
            }
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome {
            sendCount++
            return SendOutcome.Sent
        }

        override fun close() {
            closeCount++
        }
    }

    /** Counts `recvInfoFree` so "the primary's recv_info must NOT be freed at migration" is measured. */
    private class CountingFreeApi(
        delegate: QuicheApi,
    ) : QuicheApi by delegate {
        var recvInfoFrees = 0
            private set

        override fun recvInfoFree(info: QuicheRecvInfo) {
            recvInfoFrees++
        }
    }

    /**
     * Mints a distinct synthetic sockaddr per opened path and registers it with the stub, so each
     * path decodes to its own [PathKey] (see `ScriptedPathFactory` in PathValidationTimeoutTests).
     * [collideFirstPathWithPort] registers the FIRST opened path under that port instead — the
     * wildcard-bind edge case where the OS hands back the 4-tuple the active path already occupies.
     */
    private class RetirementPathFactory(
        private val api: StubQuicheApi,
        private val portBase: Int,
        private val collideFirstPathWithPort: Int? = null,
        override val localEndpointSupport: LocalEndpointSupport = LocalEndpointSupport.Bindable,
    ) : UdpChannelFactory {
        val channels = mutableListOf<RetirementChannel>()
        val opened: Int get() = channels.size

        var releases = 0
            private set

        /** The IPv4 address the next path binds on — where the platform routes from. Another address is another link. */
        var route: Long = STUB_LOOPBACK_V4

        fun portOfPath(index: Int): Int = portBase + index

        fun sockAddrOfPath(index: Int): Long = SOCKADDR_BASE + index * SOCKADDR_STRIDE

        override suspend fun openPath(
            localHost: String?,
            localPort: Int,
        ): NewPath {
            val index = channels.size + 1
            val sockAddr = SOCKADDR_BASE + index * SOCKADDR_STRIDE
            val port =
                if (index == 1 && collideFirstPathWithPort != null) collideFirstPathWithPort else portOfPath(index)
            api.registerSockAddr(sockAddr, port, route)
            val channel = RetirementChannel()
            channels += channel
            return NewPath(
                channel = channel,
                localSockAddrAddress = sockAddr,
                localSockAddrLength = 16,
                localEndpoint = QuicLocalEndpoint("127.0.0.1", port),
                release = { releases++ },
            )
        }

        private companion object {
            const val SOCKADDR_BASE = 0x3000L
            const val SOCKADDR_STRIDE = 0x100L
        }
    }

    private inner class Fixture(
        collideFirstPathWithPort: Int? = null,
        /**
         * The spare destination CIDs the stub reports, fixed for the test. Above the driver's reserve
         * a retry can afford a fresh socket; at it, a retry on the kept path's link probes that path.
         */
        spares: Long = 4L,
    ) {
        val stub =
            StubQuicheApi().apply {
                established = true
                // The peer issued spare destination CIDs, so handleMigrate reaches the probe
                // instead of answering NoSpareConnectionId.
                availableDcids = spares
                registerSockAddr(primaryAddr, primaryPort)
            }
        val api = CountingFreeApi(stub)
        val factory = RetirementPathFactory(stub, probePortBase, collideFirstPathWithPort)
        val primaryChannel = RetirementChannel()

        val driver =
            QuicheDriver(
                migration =
                    MigrationCapability.Supported(
                        peer = PinnedSockAddr(peerAddr, 16),
                        primaryLocal = PinnedSockAddr(primaryAddr, 16),
                        channelFactory = factory,
                    ),
                rawApi = api,
                conn = QuicheConn(1L),
                bufferFactory = bufferFactory,
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = primaryChannel,
                // No primary reader loop: nothing ever delivers a datagram here. Probed paths still
                // start their own readers via handleMigrate, which is what the reader assertions use.
                role = QuicRole.Client,
                ingress = DatagramIngress.ExternalPump,
                driverContext = EmptyCoroutineContext,
            )

        /** Ask the driver to migrate, exactly as every platform `QuicConnection.migrate` does. */
        suspend fun migrate(): CompletableDeferred<MigrationResult> {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(MigrationTarget.FreshLocalEndpoint, deferred))
            return deferred
        }

        /**
         * Queue the peer's `Validated` answer for path [index] and a benign wake so the driver's
         * next `afterCommand` drains it. Callers `runCurrent()` after this; the migration result is
         * then completed (or the test's `isCompleted` assertion says why not).
         */
        suspend fun validate(
            index: Int,
            v4: Long = STUB_LOOPBACK_V4,
        ) {
            stub.pathEvents += StubPathEvent(QuichePathEventType.Validated, factory.portOfPath(index), v4)
            wake()
        }

        /** The mirror of [validate]: quiche gives up on path [index]'s PATH_CHALLENGE. */
        suspend fun failValidation(index: Int) {
            stub.pathEvents += StubPathEvent(QuichePathEventType.FailedValidation, factory.portOfPath(index))
            wake()
        }

        /** One benign driver-loop wake (a no-op stream open), so `afterCommand` runs again. */
        suspend fun wake() {
            driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
        }
    }

    private fun CompletableDeferred<MigrationResult>.assertSucceeded(): MigrationResult.Succeeded {
        assertTrue(isCompleted, "migrate() never completed — the Validated event was not consumed")
        val result = getCompleted()
        assertIs<MigrationResult.Succeeded>(result, "migration did not succeed — cannot assert retirement: $result")
        return result
    }

    /**
     * **The core retirement contract, first migration.** The path migrated *from* here is the
     * original (primary) path — the one the pre-fix `teardownPath` refused to touch on principle.
     * Its socket must close at migration time; its `recv_info` and pinned sockaddr must NOT be freed
     * here (the connection setup's `onCleanup` owns those — freeing them mid-life is a UAF, freeing
     * them never is the leak; `cleanup()` is their place); and the DCID the connection used on it —
     * sequence 0, RFC 9000 §5.1.1's initial CID — must be retired per RFC 9000 §9.5.
     */
    @Test
    fun aSuccessfulMigrationRetiresThePathItMigratedFrom() =
        runTest {
            val f = Fixture()
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                assertEquals(1, f.factory.opened, "no probe path was ever opened — the test proved nothing")
                f.validate(1)
                runCurrent()
                result.assertSucceeded()

                assertEquals(
                    1,
                    f.primaryChannel.closeCount,
                    "the migrated-from (primary) path's UDP socket was left open — it stays live for the " +
                        "connection's whole life and keeps feeding quiche a recv_info for an address the " +
                        "connection no longer uses (#395)",
                )
                assertEquals(
                    0,
                    f.api.recvInfoFrees,
                    "the primary path's recv_info was freed at migration time — it is owned by cleanup()/" +
                        "onCleanup, and an early free is a use-after-free for every later RecvPacket fallback",
                )
                assertEquals(
                    listOf(0L),
                    f.stub.retiredDcids,
                    "the DCID used on the migrated-from path (initial sequence 0) was never retired — " +
                        "RFC 9000 §9.5, and the reason quiche's path table pins the old path forever",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **Second migration: the previous migrated-to path is torn down fully, and the retired DCID is
     * the one the previous `connMigrate` reported.** Unlike the primary, a probe-opened path's
     * `recv_info` and sockaddr belong to the driver, so retirement must free both — and its reader
     * coroutine (started by `handleMigrate`) must actually end, not keep feeding a dead socket.
     */
    @Test
    fun aSecondMigrationTearsDownTheFirstMigratedToPathAndItsReader() =
        runTest {
            val f = Fixture()
            f.driver.start(this)
            try {
                runCurrent()
                val first = f.migrate()
                runCurrent()
                f.validate(1)
                runCurrent()
                first.assertSucceeded()
                val firstPath = f.factory.channels[0]
                assertEquals(0, firstPath.closeCount, "the path just migrated TO must be live")

                val second = f.migrate()
                runCurrent()
                f.validate(2)
                runCurrent()
                second.assertSucceeded()

                assertEquals(
                    1,
                    firstPath.closeCount,
                    "the first migrated-to path's socket was left open after the second migration — " +
                        "`paths` grows by one live socket per migration (#395)",
                )
                runCurrent()
                assertTrue(
                    firstPath.readerCancelled.isCompleted,
                    "the retired path's reader loop is still parked in receive() — N migrations leak " +
                        "N reader coroutines (#395)",
                )
                assertEquals(
                    1,
                    f.api.recvInfoFrees,
                    "exactly the retired non-primary path's recv_info should be freed at the second " +
                        "migration (the primary's is cleanup()-owned)",
                )
                assertEquals(1, f.factory.releases, "the retired path's pinned sockaddr was never released")
                assertEquals(
                    listOf(0L, 1L),
                    f.stub.retiredDcids,
                    "the second retirement must name the DCID sequence quiche linked to the FIRST probed " +
                        "path (1) — anything else retires a CID the old path never used and leaves the " +
                        "real one pinned",
                )
                assertEquals(
                    setOf(2L),
                    f.stub.linkedDcidSeqs,
                    "after two migrations exactly one destination CID may still be linked to a path: the " +
                        "one the connection is living on (#447)",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **Egress follows the migration.** After the primary is retired, the driver's single remaining
     * path is the migrated-to one — a flush that still takes the old "single path ⇒ primary socket"
     * shortcut would write every datagram into a closed socket for the rest of the connection.
     */
    @Test
    fun flushAfterMigrationEgressesOnTheNewPathNotTheRetiredPrimarySocket() =
        runTest {
            val f = Fixture()
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                f.validate(1)
                runCurrent()
                result.assertSucceeded()
                val migratedTo = f.factory.channels[0]
                val sendsBefore = f.primaryChannel.sendCount

                f.stub.connSendQueue += 1200
                f.wake()
                runCurrent()

                assertEquals(1, migratedTo.sendCount, "the datagram never egressed on the migrated-to path")
                assertEquals(
                    sendsBefore,
                    f.primaryChannel.sendCount,
                    "a post-migration datagram egressed on the RETIRED primary socket — the single-path " +
                        "flush fast-path still points at `primary` instead of the active path (#395)",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A probe that lands on the live path's own 4-tuple is refused, not put into `paths`.**
     * `paths[key] = entry` was an unguarded overwrite: a wildcard-bind that resolves to the active
     * path's address+port would silently replace the live entry, orphaning its socket, reader and
     * recv_info with no owner and no teardown (#395 item 3). The refusal must name a retryable
     * failure, keep the live path untouched, and release everything the colliding probe acquired.
     */
    @Test
    fun aProbeThatBindsTheActivePathsEndpointIsRefusedNotOrphaned() =
        runTest {
            val f = Fixture(collideFirstPathWithPort = primaryPort)
            f.driver.start(this)
            try {
                runCurrent()
                val deferred = f.migrate()
                runCurrent()

                assertTrue(
                    deferred.isCompleted,
                    "migrate() never answered — the colliding probe silently replaced the active path's " +
                        "entry in `paths` and armed a validation that can only misroute (#395 item 3)",
                )
                val result = deferred.getCompleted()
                assertIs<MigrationResult.Unmoved.Failed.LocalPathUnavailable>(
                    result,
                    "a probe colliding with the live path's endpoint must be a retryable local failure, got $result",
                )
                assertEquals(1, f.factory.opened, "the collision must be detected on the opened path, not avoided")
                assertEquals(1, f.factory.channels[0].closeCount, "the refused probe's socket was left open")
                assertEquals(1, f.factory.releases, "the refused probe's pinned sockaddr was never released")
                assertEquals(0, f.primaryChannel.closeCount, "the live path must be untouched by the refusal")

                // The connection must still be able to migrate: the slot is free and the next probe
                // (a non-colliding one) opens and completes normally.
                val second = f.migrate()
                runCurrent()
                f.validate(2)
                runCurrent()
                second.assertSucceeded()
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **Retired SCID capacity is replenished, not issued exactly once.** RFC 9000 §5.1.1: an endpoint
     * SHOULD supply a new connection ID when the peer retires one. The one-shot `spareCidsIssued`
     * flag meant the peer of a migrating client ran dry after ~3 migrations — the client's §9.5
     * retirements freed capacity that nothing ever refilled, so migration N failed with
     * NoSpareConnectionId even once quiche's path table stopped overflowing.
     */
    @Test
    fun retiredScidCapacityIsReplenishedNotIssuedOnlyOnce() =
        runTest {
            val f = Fixture()
            f.stub.scidsLeft = 2L
            f.driver.start(this)
            try {
                runCurrent()
                assertEquals(
                    2,
                    f.stub.newScidCalls,
                    "the established driver never issued its initial spare SCIDs — the test proved nothing",
                )

                // The peer retires one of our CIDs (a RETIRE_CONNECTION_ID arrived): capacity is back.
                f.stub.scidsLeft = 1L
                f.wake()
                runCurrent()

                assertEquals(
                    3,
                    f.stub.newScidCalls,
                    "freed SCID capacity was never re-issued — issueSpareCids runs behind a one-shot flag, " +
                        "so a migrating peer runs out of CIDs to migrate to (RFC 9000 §5.1.1)",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A packet tagged with a retired path's key is dropped, not misattributed.** After retirement a
     * reader's final in-flight datagrams can still be queued behind the teardown. Falling back to
     * another path's `recv_info` would tell quiche the packet arrived on an address it did not —
     * the exact stale-attribution defect retirement exists to end. The packet is bounded collateral
     * of a path the connection already left; RFC 9002 loss recovery owns it.
     */
    @Test
    fun aPacketTaggedWithARetiredPathsKeyIsDroppedNotMisattributed() =
        runTest {
            val f = Fixture()
            var recvs = 0
            f.stub.onConnRecv = { recvs++ }
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                f.validate(1)
                runCurrent()
                result.assertSucceeded()
                val retiredKey = PathKey(family = 4, port = primaryPort, hi = 0L, lo = STUB_LOOPBACK_V4)

                // Anti-vacuity: a packet tagged with the LIVE path's key must reach quiche.
                val liveKey = PathKey(family = 4, port = probePortBase + 1, hi = 0L, lo = STUB_LOOPBACK_V4)
                sendPacket(f, liveKey)
                runCurrent()
                assertEquals(1, recvs, "a live-path packet must reach connRecv — the test harness is broken")

                sendPacket(f, retiredKey)
                runCurrent()
                assertEquals(
                    1,
                    recvs,
                    "a packet tagged with the RETIRED path's key was fed to quiche under another path's " +
                        "recv_info — stale attribution, the defect retirement exists to end",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A probe that fails validation keeps its path and its destination CID — it does not leak them.**
     *
     * `quiche_conn_probe_path` takes `lowest_available_dcid_seq()` and links it to the path it makes,
     * and `on_failed_validation()` never unlinks it, so a path the driver walks away from holds its id
     * for good (#447). The driver does not walk away: the path is kept ([PathSlot.Kept]), socket open,
     * so the next migration on the same local address can probe it again with that id instead of
     * spending a spare — the only kind a dead active path cannot earn back. What it holds is still owned
     * by exactly one path state, and [aRetryThatCanAffordAFreshSocketReplacesTheKeptPathAndRetiresItsId]
     * shows it is retired the moment the path is replaced.
     */
    @Test
    fun aFailedPathValidationKeepsTheProbePathAndItsConnectionId() =
        runTest {
            val f = Fixture()
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                assertEquals(1, f.factory.opened, "no probe path was ever opened — the test proved nothing")
                assertEquals(
                    setOf(0L, 1L),
                    f.stub.linkedDcidSeqs,
                    "the probe must have consumed a spare destination CID for this test to mean anything",
                )

                f.failValidation(1)
                runCurrent()

                assertEquals(
                    MigrationResult.Unmoved.Failed.PathNotValidated,
                    result.await(),
                    "the failed validation was not reported to the caller",
                )
                assertEquals(emptyList(), f.stub.retiredDcids, "the kept probe path's id was retired while the path still holds it")
                assertEquals(
                    setOf(0L, 1L),
                    f.stub.linkedDcidSeqs,
                    "after a failed migration the connection holds exactly its own id and the kept probe path's",
                )
                assertEquals(0, f.factory.channels[0].closeCount, "the kept probe path's socket was closed")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A retry on the kept path's link, with the pool at its reserve, probes that path again with the
     * id it already holds** — the #631 fix at the driver level.
     *
     * The fresh socket is still opened, because on a platform that assigns the local endpoint that is the
     * only way to learn which address the platform routes from; on the same address it is released
     * unprobed. Five consecutive failures link nothing new and retire nothing: the dead-link run costs
     * the connection one id, however long it lasts.
     */
    @Test
    fun aRetryOnTheKeptPathsLinkProbesItAgainWithTheConnectionIdItHolds() =
        runTest {
            val f = Fixture(spares = 1L)
            f.driver.start(this)
            try {
                runCurrent()
                repeat(5) { attempt ->
                    val result = f.migrate()
                    runCurrent()
                    f.failValidation(1)
                    runCurrent()
                    assertEquals(MigrationResult.Unmoved.Failed.PathNotValidated, result.await())
                    assertEquals(attempt + 1, f.factory.opened, "attempt ${attempt + 1} never opened its fresh socket")
                }
                assertEquals(
                    List(5) { f.factory.sockAddrOfPath(1) },
                    f.stub.probedSockAddrs,
                    "every attempt must probe the kept path; a probe of any other socket spends a spare the " +
                        "dead active path can never earn back (#631)",
                )
                assertEquals(setOf(0L, 1L), f.stub.linkedDcidSeqs, "the retries linked another id")
                assertEquals(emptyList(), f.stub.retiredDcids, "the retries retired an id the kept path still holds")
                assertEquals(0, f.factory.channels[0].closeCount, "the kept probe path's socket was closed")
                assertEquals(
                    List(4) { 1 },
                    f.factory.channels
                        .drop(1)
                        .map { it.closeCount },
                    "each retry's fresh socket must be released unprobed",
                )

                val recovered = f.migrate()
                runCurrent()
                f.validate(1)
                runCurrent()
                assertEquals(
                    MigrationResult.Succeeded(QuicLocalEndpoint("127.0.0.1", f.factory.portOfPath(1))),
                    recovered.await(),
                    "the link answered the kept path's next probe, so the connection must land on it",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **With spares to spare, a retry binds a fresh socket and the kept path's id is retired.** A pool
     * above the reserve buys a fresh 4-tuple per attempt, as every attempt had before there was a kept
     * path — and on a live active path the retirement earns a replacement, so it stays there.
     */
    @Test
    fun aRetryThatCanAffordAFreshSocketReplacesTheKeptPathAndRetiresItsId() =
        runTest {
            val f = Fixture(spares = 4L)
            f.driver.start(this)
            try {
                runCurrent()
                f.migrate()
                runCurrent()
                f.failValidation(1)
                runCurrent()
                assertEquals(setOf(0L, 1L), f.stub.linkedDcidSeqs, "the first probe was not kept")

                f.migrate()
                runCurrent()
                assertEquals(listOf(1L), f.stub.retiredDcids, "the replaced kept path's id was not retired")
                assertEquals(setOf(0L, 2L), f.stub.linkedDcidSeqs, "the fresh socket's probe must hold the only other id")
                assertEquals(1, f.factory.channels[0].closeCount, "the replaced kept path's socket was left open")
                assertEquals(1, f.factory.releases, "the replaced kept path's pinned sockaddr was never released")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A kept path on another link is replaced even at the reserve.** A fresh socket on another local
     * address says the platform routes from somewhere else now, and the reserve exists for exactly that
     * handoff: it needs one spare, because RFC 9000 §9.5 forbids sending the kept path's id from a
     * second local address.
     */
    @Test
    fun aKeptPathOnAnotherLinkIsReplacedEvenAtTheReserve() =
        runTest {
            val f = Fixture(spares = 1L)
            f.driver.start(this)
            try {
                runCurrent()
                f.migrate()
                runCurrent()
                f.failValidation(1)
                runCurrent()

                f.factory.route = OTHER_LINK_V4
                val result = f.migrate()
                runCurrent()
                assertEquals(
                    listOf(f.factory.sockAddrOfPath(1), f.factory.sockAddrOfPath(2)),
                    f.stub.probedSockAddrs,
                    "the second attempt must probe the socket on the new link, not the kept path on the old one",
                )
                assertEquals(listOf(1L), f.stub.retiredDcids, "the kept path on the old link was not retired")
                f.validate(2, OTHER_LINK_V4)
                runCurrent()
                val moved = result.assertSucceeded()
                assertEquals(f.factory.portOfPath(2), moved.localEndpoint.port)
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **…and with nothing to spend, it is retired and the migration refused.** Probing it on a link the
     * platform has left would report a result about the wrong link. Retiring it is what can earn a
     * spare back wherever the active path still carries the retirement, and the refusal is the
     * retryable [MigrationResult.Unmoved.Failed.NoSpareConnectionId].
     */
    @Test
    fun aKeptPathOnAnotherLinkWithNothingToSpendIsRetiredAndTheMigrationRefused() =
        runTest {
            val f = Fixture(spares = 1L)
            f.driver.start(this)
            try {
                runCurrent()
                f.migrate()
                runCurrent()
                f.failValidation(1)
                runCurrent()

                f.stub.availableDcids = 0L
                f.factory.route = OTHER_LINK_V4
                val result = f.migrate()
                runCurrent()
                assertEquals(MigrationResult.Unmoved.Failed.NoSpareConnectionId, result.await())
                assertEquals(2, f.factory.opened, "the fresh socket that says where the platform routes from was never opened")
                assertEquals(listOf(f.factory.sockAddrOfPath(1)), f.stub.probedSockAddrs, "nothing may be probed without an id")
                assertEquals(listOf(1L), f.stub.retiredDcids, "the kept path on the old link was not retired")
                assertEquals(1, f.factory.channels[1].closeCount, "the unprobed fresh socket was left open")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A kept path the peer answers late is switched to without another probe.** quiche goes on probing
     * a path whose id is still linked after the driver has stopped waiting, and reports the validation
     * once; a fresh PATH_CHALLENGE on an already-validated path is answered but reported never, so a
     * migration that probed it again would time out on a working path, every time.
     */
    @Test
    fun aKeptPathThePeerAnsweredLateIsSwitchedToWithoutAnotherProbe() =
        runTest {
            val f = Fixture()
            f.driver.start(this)
            try {
                runCurrent()
                f.migrate()
                runCurrent()
                f.failValidation(1)
                runCurrent()
                f.validate(1) // the late answer, with no migration waiting
                runCurrent()

                val result = f.migrate()
                runCurrent()
                assertEquals(
                    MigrationResult.Succeeded(QuicLocalEndpoint("127.0.0.1", f.factory.portOfPath(1))),
                    result.await(),
                    "the path the peer answered was not switched to",
                )
                assertEquals(listOf(f.factory.sockAddrOfPath(1)), f.stub.probedSockAddrs, "the answered path was probed again")
                assertEquals(listOf(0L), f.stub.retiredDcids, "only the migrated-from path's id is retired")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **The same leak, one exit further along: quiche validates the path and then refuses the switch.**
     *
     * `quiche_conn_migrate` can answer an error on a path it has just reported `Validated` (an
     * `OutOfIdentifiers` for the *source* CID, for instance). The driver used to complete the caller
     * with [MigrationResult.Unmoved.Failed.SwitchRejected] and leave the entry sitting in `paths`
     * holding its DCID — nothing ever retries that path, because `pendingMigration` clears and the next
     * `migrate()` opens a fresh socket, so the id and the quiche path slot were pinned exactly as a
     * failed validation pinned them.
     */
    @Test
    fun aValidatedPathWhoseSwitchQuicheRefusesIsTornDownAndItsIdRetired() =
        runTest {
            val f = Fixture()
            f.stub.connMigrateOutcomes += MigrateOutcome.Rejected(QUICHE_ERR_OUT_OF_IDENTIFIERS)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                f.validate(1)
                runCurrent()

                assertEquals(
                    MigrationResult.Unmoved.Failed.SwitchRejected(QUICHE_ERR_OUT_OF_IDENTIFIERS),
                    result.await(),
                    "the refused switch was not reported to the caller",
                )
                assertEquals(
                    1,
                    f.factory.channels[0].closeCount,
                    "the path quiche refused to switch to was left open — nothing will ever use it again",
                )
                assertEquals(
                    setOf(0L),
                    f.stub.linkedDcidSeqs,
                    "a validated-then-refused path kept its destination CID — the same leak as a failed " +
                        "validation, one exit further along (#447)",
                )
                assertEquals(
                    0,
                    f.primaryChannel.closeCount,
                    "the connection never moved, so the path it is living on must be untouched",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A stale-path collision is retried from a fresh port, once** (#583).
     *
     * `quiche_conn_probe_path` returns `INVALID_STATE` when the 4-tuple it was handed already names a
     * path whose destination connection id is gone — the state a previously abandoned probe leaves
     * behind once the kernel hands its ephemeral port back. It is **not** an exhausted pool, and it is
     * not absorbed by waiting: probing the same 4-tuple again fails identically forever, which is why
     * the replenish backoff in `FailedProbeConnectionIdTestSuite` could never rescue it and why #583
     * surfaced as a flake wearing #447's error message.
     *
     * The only thing that can clear it is a different local port, so the driver rebinds and re-probes.
     */
    @Test
    fun aStalePathCollisionRebindsAndProbesAgain() =
        runTest {
            val f = Fixture()
            // First bind lands on a port quiche still holds a dead path for; the retry is unscripted,
            // so the stub probes it normally.
            f.stub.connProbeOutcomes += ProbeOutcome.Rejected(QUICHE_ERR_INVALID_STATE)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()

                assertNotEquals(
                    MigrationResult.Unmoved.Failed.ProbeRejected(QUICHE_ERR_INVALID_STATE),
                    result.await(),
                    "the collision was reported to the caller instead of being retried from another port " +
                        "— which is the one response that can clear it",
                )
                assertEquals(
                    2,
                    f.factory.opened,
                    "the driver did not rebind: a stale-path collision retried on the same 4-tuple fails " +
                        "identically forever, so one bind means the retry never happened",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **…and only once.** A second collision on a freshly bound ephemeral port is evidence of something
     * this does not model, not of bad luck twice, so it is reported rather than retried into a loop.
     */
    @Test
    fun aSecondStalePathCollisionIsReportedRatherThanRetriedForever() =
        runTest {
            val f = Fixture()
            f.stub.connProbeOutcomes += ProbeOutcome.Rejected(QUICHE_ERR_INVALID_STATE)
            f.stub.connProbeOutcomes += ProbeOutcome.Rejected(QUICHE_ERR_INVALID_STATE)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()

                assertEquals(
                    MigrationResult.Unmoved.Failed.ProbeRejected(QUICHE_ERR_INVALID_STATE),
                    result.await(),
                    "a second collision was not reported, so the rebind is unbounded",
                )
                assertEquals(
                    2,
                    f.factory.opened,
                    "the driver bound ${f.factory.opened} times for one migration — the retry is not bounded " +
                        "to a single rebind",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A probe quiche rejects outright consumes nothing, so it must retire nothing.**
     *
     * The anti-vacuity partner of the two tests above: it would be trivial to "fix" #447 by retiring
     * something on every failure, and that would be worse than the leak. Every failure inside
     * `create_path_on_client` returns *before* `link_dcid_to_path_id`, so a rejected probe never took
     * an id — retiring one here would drop a spare the connection still owns, or the id it is using.
     */
    @Test
    fun aProbeQuicheRejectsOutrightRetiresNothing() =
        runTest {
            val f = Fixture()
            f.stub.connProbeOutcomes += ProbeOutcome.Rejected(QUICHE_ERR_OUT_OF_IDENTIFIERS)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()

                assertEquals(
                    MigrationResult.Unmoved.Failed.ProbeRejected(QUICHE_ERR_OUT_OF_IDENTIFIERS),
                    result.await(),
                )
                assertEquals(
                    emptyList(),
                    f.stub.retiredDcids,
                    "a probe quiche refused never took a destination CID, so retiring one here throws away " +
                        "a spare the connection still owns",
                )
                assertEquals(
                    setOf(0L),
                    f.stub.linkedDcidSeqs,
                    "a rejected probe must leave the connection's CID accounting exactly as it found it",
                )
                assertEquals(1, f.factory.channels[0].closeCount, "the rejected probe's socket was left open")
                assertEquals(1, f.factory.releases, "the rejected probe's pinned sockaddr was never released")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **The systemic statement of #447**: failed migrations must not accumulate.
     *
     * One leaked CID is a curiosity; the defect is that they add up — after roughly
     * `active_connection_id_limit` failed handoffs the connection could no longer migrate at all. This
     * walks five consecutive failures with spares to spare and asserts the invariant that makes that
     * impossible: after each one, the only destination CIDs linked to a path are the one the connection
     * is living on and the one the kept probe path holds — each earlier probe's id retired as its path
     * was replaced.
     */
    @Test
    fun repeatedFailedMigrationsNeverAccumulateLinkedConnectionIds() =
        runTest {
            val f = Fixture()
            f.driver.start(this)
            try {
                runCurrent()
                repeat(5) { attempt ->
                    val result = f.migrate()
                    runCurrent()
                    assertEquals(
                        attempt + 1,
                        f.factory.opened,
                        "attempt ${attempt + 1} never reached the probe — the test stopped measuring here",
                    )
                    f.failValidation(attempt + 1)
                    runCurrent()
                    assertEquals(MigrationResult.Unmoved.Failed.PathNotValidated, result.await())
                    assertEquals(
                        setOf(0L, attempt + 1L),
                        f.stub.linkedDcidSeqs,
                        "after ${attempt + 1} failed migration(s) the connection holds ids for paths it has " +
                            "replaced — this is the accumulation that ends in NoSpareConnectionId forever (#447)",
                    )
                }
                assertEquals(
                    listOf(1L, 2L, 3L, 4L),
                    f.stub.retiredDcids,
                    "each replaced probe must retire its OWN id, in order — retiring the same one twice, or " +
                        "one the connection is still using, would be a different defect wearing this fix",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A `connMigrate` that reports a different id than the probe took orphans the probe's — unless
     * the transition retires it.**
     *
     * quiche 0.29 cannot do this (`migrate()` on an existing path returns that path's own
     * `active_dcid_seq`, which is what `probe_path` linked), so this is a guard on the *shape* rather
     * than on today's behaviour: [PathSlot] makes the DCID a property of the path, and any transition
     * that does not carry it forward hands it back. Written down because the alternative — assuming the
     * two agree — is exactly the kind of unstated coupling that made #447 invisible for months.
     */
    @Test
    fun aSwitchThatReportsADifferentConnectionIdRetiresTheOneTheProbeTook() =
        runTest {
            val f = Fixture()
            f.stub.connMigrateOutcomes += MigrateOutcome.Migrated(7L)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                f.validate(1)
                runCurrent()
                result.assertSucceeded()

                assertEquals(
                    listOf(1L, 0L),
                    f.stub.retiredDcids,
                    "the probe's id (1) was displaced by the one the switch reported (7) and never handed " +
                        "back, then the migrated-from path's initial id (0) was retired as usual",
                )
                assertEquals(
                    setOf(7L),
                    f.stub.linkedDcidSeqs,
                    "only the id the connection is actually using may still be linked to a path",
                )
            } finally {
                f.driver.destroy()
            }
        }

    private suspend fun sendPacket(
        f: Fixture,
        key: PathKey,
    ) {
        val buf = bufferFactory.allocate(64)
        f.driver.commands.send(QuicheCmd.RecvPacket(buf, 64, PacketSource.FromPath(key)))
    }

    private companion object {
        /** [StubQuicheApi]'s default `sockAddrV4` answer for a registered sockaddr (127.0.0.1). */
        const val STUB_LOOPBACK_V4 = 0x7F000001L

        /** A second local address (10.0.0.2) — another link, as the driver sees it. */
        const val OTHER_LINK_V4 = 0x0A000002L
    }
}
