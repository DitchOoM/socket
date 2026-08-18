package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a connection that cannot migrate actually *says*.
 *
 * The old `MigrationResult.Unsupported` covered three unrelated facts — this is a server, the caller
 * forbade migration, this backend has no path factory — so a caller who got it could not tell "you asked
 * the wrong side" from "you configured it off" from "this build cannot do it". Each is now its own
 * [MigrationResult.Unmoved.Impossible] leaf, and `handleMigrate`'s job is a translation of
 * [MigrationCapability], not a judgement. These tests pin that translation, because it is the D7
 * conflation and nothing else covers it.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, so a `commonTest` home
 * covered every platform *except* the one that ships this backend to users: Android is the only target
 * that runs quiche over JNI, and it is where issue #393 was found in the field. This directory is
 * `srcDir`'d into both source sets, so the same source runs unchanged on jvm/apple/linux **and** on a
 * real device — the move adds the lane that was missing and takes none away. See DitchOoM/socket#390.
 */
class MigrationCapabilityAnswerTests {
    private val bufferFactory = BufferFactory.deterministic()

    private fun driverWith(capability: MigrationCapability): QuicheDriver =
        QuicheDriver(
            migration = capability,
            rawApi = StubQuicheApi(),
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = true,
            isServer = false,
            driverContext = EmptyCoroutineContext,
        )

    private fun assertAnswers(
        capability: MigrationCapability,
        expected: MigrationResult,
    ) = runTest {
        val driver = driverWith(capability)
        driver.start(this)
        try {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(MigrationTarget.FreshLocalEndpoint, deferred))
            runCurrent()
            assertEquals(expected, deferred.await())
        } finally {
            driver.destroy()
        }
    }

    @Test
    fun aServerConnectionSaysSoRatherThanBlamingTheBackend() =
        assertAnswers(MigrationCapability.ServerConnection, MigrationResult.Unmoved.Impossible.ServerConnection)

    @Test
    fun aForbiddenPolicySaysSoRatherThanBlamingTheBackend() =
        assertAnswers(MigrationCapability.PolicyForbids, MigrationResult.Unmoved.Impossible.PolicyForbids)

    @Test
    fun aBackendWithNoPathFactorySaysSo() =
        assertAnswers(MigrationCapability.BackendCannotMigrate, MigrationResult.Unmoved.Impossible.BackendCannotMigrate)

    /**
     * The client-side translation of the public policy, in the one place all three platform connection
     * setups share it. [MigrationPolicy.Forbidden] must **not** build a path factory it would refuse to
     * use, which is why the wiring is a lambda — asserted here by the fact that it is never invoked.
     */
    @Test
    fun clientCapabilityFollowsThePolicyAndOnlyBuildsWiringWhenItCan() {
        var wiringBuilt = 0
        val wiring = {
            wiringBuilt++
            MigrationCapability.Supported(
                peer = PinnedSockAddr(1L, 16),
                primaryLocal = PinnedSockAddr(2L, 16),
                channelFactory = NeverCalledChannelFactory,
            )
        }

        assertEquals(MigrationCapability.PolicyForbids, clientMigrationCapability(MigrationPolicy.Forbidden, wiring))
        assertEquals(0, wiringBuilt, "a forbidden policy must not build migration wiring")

        // Manual and Automatic differ in WHO calls migrate(), not in whether the connection can.
        val manual = clientMigrationCapability(MigrationPolicy.Manual, wiring)
        val automatic = clientMigrationCapability(MigrationPolicy.Automatic, wiring)
        assertEquals(2, wiringBuilt)
        assertEquals(manual, automatic)
    }

    /** Never opened: [clientMigrationCapability] must not even construct wiring for a forbidden policy. */
    private object NeverCalledChannelFactory : UdpChannelFactory {
        override val localEndpointSupport: LocalEndpointSupport = LocalEndpointSupport.Bindable

        override suspend fun openPath(
            localHost: String?,
            localPort: Int,
        ): NewPath = error("this factory exists only to be un-used")
    }
}
