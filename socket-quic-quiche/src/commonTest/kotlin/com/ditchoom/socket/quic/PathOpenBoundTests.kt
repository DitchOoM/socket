@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Opening a migration path must never park the driver (#613).
 *
 * Measured on an iPhone with a connection to an IPv6 peer over cellular: a v4-only Wi-Fi joined,
 * the automatic reactor asked for a migration onto it, and Network.framework held the new socket in
 * `waiting` for 95 s. The driver awaited that open *inline in its command loop*, so for 95 s it sent
 * nothing — no echoes, no ACKs, no keepalive — on the cellular path it still had, and the peer's
 * stateful path expired underneath it. The probe that finally went out found a dead connection.
 *
 * The open is a platform call with no bound of its own, so the driver gives it one — the same RFC
 * 9000 §8.2.4 budget path validation gets — and runs it beside the loop rather than on it. These
 * tests pin both halves: the loop keeps answering commands while an open is pending, and an open that
 * never finishes ends the attempt typed, with the lane free for the next one.
 */
class PathOpenBoundTests {
    private val bufferFactory = BufferFactory.deterministic()

    private val peerAddr = 0x1100L
    private val primaryAddr = 0x1200L
    private val primaryPort = 40000
    private val probePortBase = 41000

    /**
     * `3 × max(currentPto, initialPto)` with no path stats bound — the same 2997 ms
     * `PathValidationTimeoutTests` spells out, because the open borrows validation's budget on purpose.
     */
    private val expectedBudget = 2997.milliseconds

    /**
     * A [UdpChannelFactory] whose `openPath` does not return until the test says so — the shape of a
     * platform still trying to open a socket. Records whether the driver gave up on it (cancellation).
     */
    private class GatedPathFactory(
        private val api: StubQuicheApi,
        private val portBase: Int,
        override val localEndpointSupport: LocalEndpointSupport = LocalEndpointSupport.Bindable,
    ) : UdpChannelFactory {
        val gate = CompletableDeferred<Unit>()
        var openCalls = 0
            private set
        var cancelledOpens = 0
            private set
        val channels = mutableListOf<StubUdpChannel>()
        var releases = 0
            private set

        override suspend fun openPath(
            localHost: String?,
            localPort: Int,
        ): NewPath {
            openCalls++
            try {
                gate.await()
            } catch (ce: CancellationException) {
                cancelledOpens++
                throw ce
            }
            val index = channels.size + 1
            val sockAddr = SOCKADDR_BASE + index * SOCKADDR_STRIDE
            val port = portBase + index
            api.registerSockAddr(sockAddr, port)
            val channel = StubUdpChannel()
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
            const val SOCKADDR_BASE = 0x2000L
            const val SOCKADDR_STRIDE = 0x100L
        }
    }

    private inner class Fixture(
        scope: TestScope,
    ) {
        val stub =
            StubQuicheApi().apply {
                established = true
                availableDcids = 1L
                registerSockAddr(primaryAddr, primaryPort)
            }
        val factory = GatedPathFactory(stub, probePortBase)

        val driver =
            QuicheDriver(
                migration =
                    MigrationCapability.Supported(
                        peer = PinnedSockAddr(peerAddr, 16),
                        primaryLocal = PinnedSockAddr(primaryAddr, 16),
                        channelFactory = factory,
                    ),
                rawApi = stub,
                conn = QuicheConn(1L),
                bufferFactory = bufferFactory,
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = StubUdpChannel(),
                role = QuicRole.Client,
                ingress = DatagramIngress.ExternalPump,
                clock = SimClock(scope.testScheduler),
                driverContext = EmptyCoroutineContext,
            )

        suspend fun migrate(): CompletableDeferred<MigrationResult> {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(MigrationTarget.FreshLocalEndpoint, deferred))
            return deferred
        }

        /** A command the loop must answer if — and only if — it is still running. */
        suspend fun wake(): CompletableDeferred<StreamSlot> {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred))
            return deferred
        }
    }

    /**
     * **The regression.** A migration is asked for and the platform has not finished opening the
     * socket. The loop must go on answering commands — in the field, this is every echo, ACK and
     * keepalive the connection's *current* path stopped carrying for 95 s.
     */
    @Test
    fun theLoopKeepsAnsweringCommandsWhileAPathOpenIsPending() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val migration = f.migrate()
                runCurrent()
                assertEquals(1, f.factory.openCalls, "handleMigrate never asked the platform for a socket")
                assertFalse(migration.isCompleted, "migrate() answered before the platform had opened anything")

                val answered = f.wake()
                runCurrent()
                assertTrue(
                    answered.isCompleted,
                    "a command sent while the path open is pending was never answered — the driver loop is " +
                        "parked inside the open, which is #613: nothing is sent on the current path until " +
                        "the platform gives up on the new one",
                )
            } finally {
                // A driver parked inside the open (the defect) could never be destroyed; let it out first
                // so a red run fails on its assertion rather than hanging here.
                f.factory.gate.complete(Unit)
                f.driver.destroy()
            }
        }

    /**
     * An open that never finishes ends the attempt on the budget, typed, and leaves the lane free. The
     * near-miss half stops an over-eager bound from passing; the second `migrate()` is the wedge check.
     */
    @Test
    fun anOpenThatNeverFinishesEndsTheAttemptTypedAtTheBudgetAndFreesTheLane() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val first = f.migrate()
                runCurrent()
                assertEquals(1, f.factory.openCalls)

                testScheduler.advanceTimeBy(expectedBudget - 1.milliseconds)
                runCurrent()
                assertFalse(first.isCompleted, "the open was abandoned before its budget ($expectedBudget) elapsed")

                testScheduler.advanceTimeBy(2.milliseconds)
                runCurrent()
                assertTrue(first.isCompleted, "the budget passed and migrate() is still suspended on an open that will never finish")
                assertEquals(MigrationResult.Unmoved.Failed.LocalPathOpenTimedOut(expectedBudget), first.await())
                assertEquals(1, f.factory.cancelledOpens, "the platform open was left running after the driver gave up on it")
                assertEquals(0, f.factory.channels.size, "no socket was ever opened, so none may exist")

                val second = f.migrate()
                runCurrent()
                assertEquals(2, f.factory.openCalls, "the lane was not freed: the second attempt never reached the platform")
                assertFalse(second.isCompleted, "the second attempt should be waiting on its own open, not already answered")
            } finally {
                // A driver parked inside the open (the defect) could never be destroyed; let it out first
                // so a red run fails on its assertion rather than hanging here.
                f.factory.gate.complete(Unit)
                f.driver.destroy()
            }
        }

    /** The happy half: an open that finishes late but inside the budget continues into a probe. */
    @Test
    fun anOpenThatFinishesInsideTheBudgetContinuesIntoProbing() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val migration = f.migrate()
                runCurrent()
                testScheduler.advanceTimeBy(1000.milliseconds)
                runCurrent()
                assertEquals(QuicPathState.Original, f.driver.pathState.value, "nothing is probing before the socket exists")

                f.factory.gate.complete(Unit)
                runCurrent()
                assertEquals(1, f.factory.channels.size)
                assertIs<QuicPathState.Probing>(f.driver.pathState.value, "an opened socket must be probed, not dropped")
                assertFalse(migration.isCompleted, "the probe is outstanding; migrate() must not have answered yet")
            } finally {
                // A driver parked inside the open (the defect) could never be destroyed; let it out first
                // so a red run fails on its assertion rather than hanging here.
                f.factory.gate.complete(Unit)
                f.driver.destroy()
            }
        }

    /** The connection dies while the platform is still opening: the attempt fails typed and the open is cancelled. */
    @Test
    fun aConnectionClosingMidOpenFailsTheAttemptAndCancelsTheOpen() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            runCurrent()
            val migration = f.migrate()
            runCurrent()
            assertEquals(1, f.factory.openCalls)

            assertNotNull(
                withTimeoutOrNull(5.seconds) { f.driver.destroy() },
                "destroy() never returned — the loop is parked inside the open and cannot even close",
            )
            runCurrent()
            assertTrue(migration.isCompleted, "a migration still opening when the connection died never completed")
            assertEquals(MigrationResult.Unmoved.Impossible.ConnectionClosed, migration.await())
            assertEquals(1, f.factory.cancelledOpens, "the platform open outlived the connection")
        }
}
