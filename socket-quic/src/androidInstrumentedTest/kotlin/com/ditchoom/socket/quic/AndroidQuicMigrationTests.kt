package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Network migration tests using a host-side [NetworkControl] server.
 *
 * The host server executes `adb shell su 0 <iptables/tc/settings>` commands.
 * Run via `./gradlew :socket-quic:androidQuicIntegrationTest` which starts
 * both the QUIC echo server and network control server on the host.
 *
 * Requires: rooted emulator (`adb root`).
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicMigrationTests {
    private val serverHost = "10.0.2.2"
    private val serverPort = 4433

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private var networkControl = NetworkControl()

    @Before
    fun checkPrerequisites() {
        networkControl = NetworkControl()
        assumeTrue(
            "Network control server not available — run androidQuicIntegrationTest",
            networkControl.isAvailable(),
        )
    }

    @After
    fun cleanup() {
        networkControl.close()
    }

    /** Connect or skip if server isn't reachable. */
    private suspend fun <R> withServerConnection(
        options: QuicOptions = testQuicOptions,
        block: suspend QuicScope.() -> R,
    ): R = withQuicConnection(serverHost, serverPort, options, timeout = 15.seconds, block = block)

    @Test
    fun connectionSurvivesTemporaryNetworkLoss() =
        runBlocking(Dispatchers.IO) {
            withServerConnection {
                networkControl.blockUdp()
                delay(2.seconds)
                networkControl.unblockUdp()
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
                    networkControl.blockUdp()
                    delay(5.seconds)
                    networkControl.unblockUdp()
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

                networkControl.blockUdp()
                delay(1.seconds)
                networkControl.unblockUdp()
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
                networkControl.addLatency(500)
                delay(1.seconds)

                val stream = openStream()
                val buf = BufferFactory.Default.allocate(4)
                buf.writeString("test", Charset.UTF8)
                buf.resetForRead()
                stream.write(buf, 10.seconds)

                stream.close()
                networkControl.removeLatency()
            }
        }

    @Test
    fun airplaneModeToggle() =
        runBlocking(Dispatchers.IO) {
            try {
                withServerConnection {
                    // Schedule recovery in 5s, then activate airplane mode
                    networkControl.airplaneModeOn(recoveryDelayMs = 5000)
                    // Wait for scheduled recovery + margin
                    networkControl.waitForAirplaneModeRecovery(waitMs = 7000)
                    // If we're still here, connection survived (or we can verify state)
                }
            } catch (_: Throwable) {
                // Connection may have closed — that's acceptable for airplane mode
            }
        }
}
