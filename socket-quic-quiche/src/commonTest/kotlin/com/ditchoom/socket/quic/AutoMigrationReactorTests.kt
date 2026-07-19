package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the [wireAutoMigration] reactor branches, isolated from any real backend.
 *
 * The end-to-end [QuicAutoMigrationTests] proves the happy path over a live quiche connection; here we
 * drive the reactor with a [RecordingQuicConnection] (which only records [QuicConnection.migrate]
 * calls) and a scriptable [SimNetworkMonitor], so every guard — the two opt-outs, the AlwaysAvailable
 * short-circuit, the `Unidentified` filter, the connect-time-baseline `drop(1)`, repeated handoffs, and
 * the `Unsupported`-backend self-cancel — is asserted deterministically with no network at all.
 *
 * Runs on every platform (no native lib), under [runTest] with an [UnconfinedTestDispatcher] so a
 * `networkId` change synchronously drives the collector and `migrate` bookkeeping before we assert.
 */
class AutoMigrationReactorTests {
    private val wifi = NetworkId.Link(NetworkKind.Wifi, 1L)
    private val cellular = NetworkId.Link(NetworkKind.Cellular, 2L)
    private val ethernet = NetworkId.Link(NetworkKind.Ethernet, 3L)

    /** A [QuicConnection] that records `migrate` calls and returns a scripted [MigrationResult]. */
    private class RecordingQuicConnection(
        dispatcher: CoroutineContext,
        private val result: MigrationResult,
    ) : QuicConnection {
        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext = dispatcher + job

        val migrateArgs = mutableListOf<Pair<String?, Int>>()
        val migrateCount: Int get() = migrateArgs.size

        override suspend fun migrate(
            localHost: String?,
            localPort: Int,
        ): MigrationResult {
            migrateArgs += localHost to localPort
            return result
        }

        fun stop() = job.cancel()

        // --- Unused by the reactor; present only to satisfy the interface. ---
        override val bufferFactory: BufferFactory get() = BufferFactory.Default
        override val state: StateFlow<QuicConnectionState> = MutableStateFlow(QuicConnectionState.Idle)

        override suspend fun openStream(): QuicByteStream = error("unused")

        override suspend fun acceptStream(): QuicByteStream = error("unused")

        override fun streams(): Flow<QuicByteStream> = throw UnsupportedOperationException("unused")

        override suspend fun close(error: QuicError) = Unit
    }

    private fun options(
        monitor: NetworkMonitor?,
        autoMigrate: Boolean = true,
        disableActiveMigration: Boolean = false,
    ) = QuicOptions(
        alpnProtocols = listOf("test"),
        autoMigrateOnNetworkChange = autoMigrate,
        disableActiveMigration = disableActiveMigration,
        networkMonitor = monitor,
    )

    private fun runReactor(
        monitor: NetworkMonitor?,
        autoMigrate: Boolean = true,
        disableActiveMigration: Boolean = false,
        migrateResult: MigrationResult = MigrationResult.Succeeded(null, 0),
        body: (RecordingQuicConnection) -> Unit,
    ) = runTest {
        val connection = RecordingQuicConnection(UnconfinedTestDispatcher(testScheduler), migrateResult)
        try {
            wireAutoMigration(options(monitor, autoMigrate, disableActiveMigration), connection)
            body(connection)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun disableActiveMigrationNeverObserves() {
        val monitor = SimNetworkMonitor(initialNetworkId = wifi)
        runReactor(monitor, disableActiveMigration = true) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(0, conn.migrateCount, "disableActiveMigration must not react to network changes")
        }
    }

    @Test
    fun autoMigrateDisabledNeverObserves() {
        val monitor = SimNetworkMonitor(initialNetworkId = wifi)
        runReactor(monitor, autoMigrate = false) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(0, conn.migrateCount, "autoMigrateOnNetworkChange=false must not react")
        }
    }

    @Test
    fun alwaysAvailableMonitorLaunchesNoCollector() =
        runReactor(NetworkMonitor.AlwaysAvailable) { conn ->
            // AlwaysAvailable never changes its (Unidentified) identity; the reactor short-circuits
            // before even launching, so there is nothing to drive and no migration can occur.
            assertEquals(0, conn.migrateCount)
            assertTrue(
                conn.coroutineContext[Job]!!.children.none(),
                "AlwaysAvailable must not launch an observer",
            )
        }

    @Test
    fun firstIdentifiedLinkIsBaselineNotAMigration() {
        val monitor = SimNetworkMonitor(initialNetworkId = NetworkId.Unidentified)
        runReactor(monitor) { conn ->
            // The first *identified* link after connect is the baseline the connection already lives on.
            monitor.setNetworkId(wifi)
            assertEquals(0, conn.migrateCount, "the first identified link is the connect-time baseline")
            // Only a subsequent, distinct link is a real handoff.
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
        }
    }

    @Test
    fun distinctLinkChangeMigratesWithEphemeralDefaults() {
        val monitor = SimNetworkMonitor(initialNetworkId = wifi)
        runReactor(monitor) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
            // migrate() is called with the ephemeral-socket defaults: no host, port 0.
            assertEquals(null to 0, conn.migrateArgs.single())
        }
    }

    @Test
    fun unidentifiedEmissionsAreIgnored() {
        val monitor = SimNetworkMonitor(initialNetworkId = wifi)
        runReactor(monitor) { conn ->
            // A link momentarily vanishing (Unidentified) is not a migrate target and is not a baseline.
            monitor.setNetworkId(NetworkId.Unidentified)
            assertEquals(0, conn.migrateCount)
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount, "the drop-1 baseline must survive an Unidentified gap")
        }
    }

    @Test
    fun everyDistinctHandoffMigrates() {
        val monitor = SimNetworkMonitor(initialNetworkId = wifi)
        runReactor(monitor) { conn ->
            monitor.setNetworkId(cellular)
            monitor.setNetworkId(ethernet)
            assertEquals(2, conn.migrateCount, "each distinct link change is its own migration")
        }
    }

    @Test
    fun unsupportedBackendStopsObservingAfterFirstAttempt() {
        val monitor = SimNetworkMonitor(initialNetworkId = wifi)
        runReactor(monitor, migrateResult = MigrationResult.Unsupported) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
            // Unsupported → the observer cancels itself; further changes must not call migrate again.
            monitor.setNetworkId(ethernet)
            assertEquals(1, conn.migrateCount, "an Unsupported backend must stop reacting")
        }
    }
}
