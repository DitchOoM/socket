package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.netctrl.NetCtrlResponse
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Network migration tests using a host-side [NetworkControl] server.
 *
 * The host server executes `adb shell su 0 <iptables/tc/settings>` commands.
 * Run via `./gradlew :socket-quic-quiche:androidQuicIntegrationTest`, which starts
 * both the QUIC echo server and the network control server on the host and carries
 * each one's device-reachable address down as instrumentation arguments — the two
 * addresses differ, because the control channel rides an `adb reverse tcp:` mapping
 * and the UDP echo harness cannot (see [HarnessEndpoints]).
 *
 * Requires: rooted emulator (`adb root`).
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicMigrationTests {
    private lateinit var server: HarnessEndpoint

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private var networkControl: NetworkControl? = null

    /**
     * Both harness servers, each resolved and each failing in its own name.
     *
     * The predecessor was `assumeTrue("Network control server not available", isAvailable())` beside
     * a hardcoded `10.0.2.2` QUIC address — so on real hardware all five tests vanished into a green
     * run without either address ever being printed.
     */
    @Before
    fun checkPrerequisites() {
        server = HarnessEndpoints.quicEcho.addressOrSkip(AndroidQuicMigrationTests::class).endpoint
        val ctrl = HarnessEndpoints.netCtrl.addressOrSkip(AndroidQuicMigrationTests::class)
        val client = NetworkControl(ctrl.endpoint)
        // Assigned before the probe so @After closes the socket even when the probe is what fails.
        networkControl = client
        val failure = client.probe()
        if (failure != null) ctrl.skipUnanswered(AndroidQuicMigrationTests::class, failure)

        // The server answering is not the server being ABLE to impair anything (#389). Every
        // impairment runs as `adb shell su 0 …`; on a device without root each fails, and the server
        // used to log that as non-fatal and reply Ok — so all five of these tests passed on a
        // physical SM-F956U1 with no UDP blocked, no latency added and airplane mode never toggled.
        // A vacuous pass is worse than a skip: a skip is at least countable.
        //
        // HostCannotProvideIt on purpose: a lane cannot root a handset, so this must not turn a
        // SOCKET_REQUIRE_ALL_TESTS=1 run red — it must be *counted*, and the reason must name the
        // capability rather than the symptom.
        when (val capability = client.queryImpairment()) {
            is NetCtrlResponse.ImpairmentAvailable -> Unit
            is NetCtrlResponse.ImpairmentUnavailable -> {
                recordSkip(
                    AndroidQuicMigrationTests::class,
                    SkipReason.HostBehaviourDiffers(capability.why),
                    SkipGate.HostCannotProvideIt("a rooted device (`su 0`) for iptables/tc/airplane-mode impairment"),
                )
                assumeTrue(capability.why, false)
            }
            else ->
                fail(
                    "the control server answered QueryImpairment with $capability — it can only be " +
                        "ImpairmentAvailable or ImpairmentUnavailable, so this is a host/device version skew",
                )
        }
    }

    @After
    fun cleanup() {
        networkControl?.close()
        networkControl = null
    }

    /** The control channel, which [checkPrerequisites] has already proven answers. */
    private val control: NetworkControl
        get() = checkNotNull(networkControl) { "checkPrerequisites did not run" }

    private suspend fun <R> withServerConnection(
        options: QuicOptions = testQuicOptions,
        block: suspend QuicScope.() -> R,
    ): R = withQuicConnection(server.host, server.port, options, timeout = 15.seconds, block = block)

    /**
     * Each UDP-block test asserts its premise before its conclusion: the block dropped this app's
     * datagrams (the host's DROP counter moved, and what was sent during it went unanswered). Without
     * that, a block that never reached the app's traffic passes every one of these tests on a healthy
     * network (#702).
     */
    private fun assertTheBlockDroppedTheAppsDatagrams() {
        val drops = control.udpDrops()
        if (drops.ipv4Packets + drops.ipv6Packets == 0L) {
            fail("the UDP block dropped none of this app's datagrams, so the test would run on a healthy network.\n${drops.outputChains}")
        }
    }

    private suspend fun QuicByteStream.send(payload: String) {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
    }

    private suspend fun QuicByteStream.receive(deadline: Duration): ScopedRead<String> =
        read(deadline) {
            it.readString(it.remaining(), Charset.UTF8)
        }

    private suspend fun QuicByteStream.assertEchoes(payload: String) {
        send(payload)
        assertEquals(ScopedRead.Data(payload), receive(5.seconds))
    }

    /** Nothing may come back while the block holds: the echo of what was sent during it is dropped. */
    private suspend fun QuicByteStream.assertNothingArrivesFor(window: Duration) {
        val arrived = withTimeoutOrNull(window) { receive(window * 10) }
        if (arrived != null) fail("the peer answered through the UDP block: $arrived")
    }

    @Test
    fun connectionSurvivesTemporaryNetworkLoss() =
        runBlocking(Dispatchers.IO) {
            // Keep-alive PINGs are what the block drops here; the connection must outlive them.
            withServerConnection(testQuicOptions.copy(keepAliveInterval = 500.milliseconds)) {
                val stream = openStream()
                stream.assertEchoes("before")
                control.blockUdp()
                stream.assertNothingArrivesFor(2.seconds)
                assertTheBlockDroppedTheAppsDatagrams()
                control.unblockUdp()
                stream.assertEchoes("after")
            }
        }

    @Test
    fun connectionTimesOutOnProlongedLoss() =
        runBlocking(Dispatchers.IO) {
            // Keep-alive would hold an unimpaired connection open indefinitely, so an idle-out is the block's doing.
            val options = testQuicOptions.copy(idleTimeout = 3.seconds, keepAliveInterval = 500.milliseconds)
            val closed =
                assertFailsWith<QuicCloseException> {
                    withServerConnection(options) {
                        val stream = openStream()
                        stream.assertEchoes("before")
                        control.blockUdp()
                        stream.send("during")
                        fail("the echo of a write made during the block arrived: ${stream.receive(30.seconds)}")
                    }
                }
            assertTheBlockDroppedTheAppsDatagrams()
            assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), closed.closeReason)
        }

    @Test
    fun dataFlowResumesAfterNetworkRecovery() =
        runBlocking(Dispatchers.IO) {
            withServerConnection {
                val stream = openStream()
                stream.assertEchoes("part1")
                control.blockUdp()
                stream.send("part2")
                stream.assertNothingArrivesFor(1.seconds)
                assertTheBlockDroppedTheAppsDatagrams()
                control.unblockUdp()
                // Retransmission carries part2 once the block lifts, and its echo arrives.
                assertEquals(ScopedRead.Data("part2"), stream.receive(10.seconds))
                stream.close()
            }
        }

    @Test
    fun connectionWithHighLatency() =
        runBlocking(Dispatchers.IO) {
            withServerConnection {
                control.addLatency(500)
                delay(1.seconds)

                val stream = openStream()
                val buf = BufferFactory.Default.allocate(4)
                buf.writeString("test", Charset.UTF8)
                buf.resetForRead()
                stream.write(buf, 10.seconds)

                stream.close()
                control.removeLatency()
            }
        }

    /**
     * The connection may or may not outlive the outage; what this test holds is that the outage
     * happened and that the device is back on the network before the next test connects.
     */
    @Test
    fun airplaneModeToggle() =
        runBlocking(Dispatchers.IO) {
            DeviceNetworkWatch(InstrumentationRegistry.getInstrumentation().targetContext, server).use { device ->
                try {
                    withServerConnection {
                        control.airplaneModeOn(recoveryDelayMs = AIRPLANE_RECOVERY_DELAY.inWholeMilliseconds)
                        device.awaitAirplaneMode(AirplaneMode.On, bound = 5.seconds)
                        device.awaitAirplaneMode(AirplaneMode.Off, bound = AIRPLANE_RECOVERY_DELAY + 10.seconds)
                    }
                } catch (_: QuicCloseException) {
                    // The outage ended the connection.
                } finally {
                    device.awaitAirplaneMode(AirplaneMode.Off, bound = AIRPLANE_RECOVERY_DELAY + 10.seconds)
                    device.awaitRouteToHarness(bound = 30.seconds)
                    control.reconnect()
                }
            }
        }

    private companion object {
        val AIRPLANE_RECOVERY_DELAY = 5.seconds
    }
}
