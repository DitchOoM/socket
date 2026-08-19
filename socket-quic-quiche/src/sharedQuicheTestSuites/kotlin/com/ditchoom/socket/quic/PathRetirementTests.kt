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
            dest: PathKey?,
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

        fun portOfPath(index: Int): Int = portBase + index

        override suspend fun openPath(
            localHost: String?,
            localPort: Int,
        ): NewPath {
            val index = channels.size + 1
            val sockAddr = SOCKADDR_BASE + index * SOCKADDR_STRIDE
            val port =
                if (index == 1 && collideFirstPathWithPort != null) collideFirstPathWithPort else portOfPath(index)
            api.registerSockAddr(sockAddr, port)
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
    ) {
        val stub =
            StubQuicheApi().apply {
                established = true
                // The peer issued spare destination CIDs, so handleMigrate reaches the probe
                // instead of answering NoSpareConnectionId.
                availableDcids = 4L
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
                clientMode = false,
                isServer = false,
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
        suspend fun validate(index: Int) {
            stub.pathEvents += StubPathEvent(QuichePathEventType.Validated, factory.portOfPath(index))
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
            f.stub.connMigrateOutcomes += MigrateOutcome.Migrated(5L)
            f.stub.connMigrateOutcomes += MigrateOutcome.Migrated(9L)
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
                    listOf(0L, 5L),
                    f.stub.retiredDcids,
                    "the second retirement must name the DCID sequence the first connMigrate reported (5) — " +
                        "anything else retires a CID the old path never used and leaves the real one pinned",
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
                val retiredKey = PathKey.V4(port = primaryPort, addr = STUB_LOOPBACK_V4)

                // Anti-vacuity: a packet tagged with the LIVE path's key must reach quiche.
                val liveKey = PathKey.V4(port = probePortBase + 1, addr = STUB_LOOPBACK_V4)
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

    private suspend fun sendPacket(
        f: Fixture,
        key: PathKey,
    ) {
        val buf = bufferFactory.allocate(64)
        f.driver.commands.send(QuicheCmd.RecvPacket(buf, 64, PacketSource.FromPath(key)))
    }

    private companion object {
        /** [StubQuicheApi]'s `sockAddrV4` answer for every registered sockaddr (127.0.0.1). */
        const val STUB_LOOPBACK_V4 = 0x7F000001L
    }
}
