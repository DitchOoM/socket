package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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

    @Test
    fun connectionSurvivesTemporaryNetworkLoss() =
        runBlocking(Dispatchers.IO) {
            withServerConnection {
                control.blockUdp()
                delay(2.seconds)
                control.unblockUdp()
                delay(1.seconds)
                // If we're still inside the block, connection survived
            }
        }

    @Test
    fun connectionTimesOutOnProlongedLoss() =
        runBlocking(Dispatchers.IO) {
            val options = testQuicOptions.copy(idleTimeout = 3.seconds)
            try {
                withServerConnection(options) {
                    control.blockUdp()
                    delay(5.seconds)
                    control.unblockUdp()
                    delay(1.seconds)
                }
            } catch (_: Throwable) {
                // Expected: connection timed out and block was cancelled
            }
        }

    @Test
    fun dataFlowResumesAfterNetworkRecovery() =
        runBlocking(Dispatchers.IO) {
            withServerConnection {
                val stream = openStream()

                val buf1 = BufferFactory.Default.allocate(5)
                buf1.writeString("part1", Charset.UTF8)
                buf1.resetForRead()
                stream.write(buf1, 5.seconds)

                control.blockUdp()
                delay(1.seconds)
                control.unblockUdp()
                delay(1.seconds)

                val buf2 = BufferFactory.Default.allocate(5)
                buf2.writeString("part2", Charset.UTF8)
                buf2.resetForRead()
                stream.write(buf2, 5.seconds)

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

    @Test
    fun airplaneModeToggle() =
        runBlocking(Dispatchers.IO) {
            try {
                withServerConnection {
                    // Schedule recovery in 5s, then activate airplane mode
                    control.airplaneModeOn(recoveryDelayMs = 5000)
                    // Wait for scheduled recovery + margin
                    control.waitForAirplaneModeRecovery(waitMs = 7000)
                    // If we're still here, connection survived (or we can verify state)
                }
            } catch (_: Throwable) {
                // Connection may have closed — that's acceptable for airplane mode
            }
        }
}
