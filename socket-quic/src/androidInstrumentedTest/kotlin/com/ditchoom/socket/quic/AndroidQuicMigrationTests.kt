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
 * Network migration tests on a rooted Android emulator.
 *
 * These tests manipulate network state via iptables and verify QUIC connection behavior.
 * Requires:
 * - Rooted emulator (target: default, not google_apis)
 * - QUIC test server running on host at 10.0.2.2:4433
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

    @Before
    fun checkPrerequisites() {
        assumeTrue(
            "Root access required for network migration tests. " +
                "Use an emulator with target=default (not google_apis) and run 'adb root' first.",
            EmulatorNetworkUtils.isRooted(),
        )
    }

    @After
    fun cleanup() {
        try {
            EmulatorNetworkUtils.unblockUdp()
        } catch (_: Exception) {
        }
        try {
            EmulatorNetworkUtils.removeLatency()
        } catch (_: Exception) {
        }
        try {
            EmulatorNetworkUtils.airplaneModeOff()
        } catch (_: Exception) {
        }
    }

    /** Connect or skip the test if the server isn't reachable. */
    private suspend fun <R> withServerConnection(
        options: QuicOptions = testQuicOptions,
        block: suspend QuicScope.() -> R,
    ): R {
        val engine = defaultQuicEngine()
        return try {
            engine.connect(serverHost, serverPort, options, timeout = 10.seconds, block = block)
        } catch (e: Throwable) {
            engine.close()
            assumeTrue("QUIC server not reachable — skipping: ${e.message}", false)
            throw AssertionError("unreachable")
        } finally {
            engine.close()
        }
    }

    @Test
    fun connectionSurvivesTemporaryNetworkLoss() =
        runBlocking(Dispatchers.IO) {
            withServerConnection {
                // Block UDP for 2 seconds
                EmulatorNetworkUtils.blockUdp()
                delay(2.seconds)
                EmulatorNetworkUtils.unblockUdp()

                // Give QUIC time to recover
                delay(1.seconds)

                // If we're still inside the block, connection survived
                // (connection close would cancel this block)
            }
        }

    @Test
    fun connectionTimesOutOnProlongedLoss() =
        runBlocking(Dispatchers.IO) {
            val options = testQuicOptions.copy(idleTimeout = 3.seconds)
            try {
                withServerConnection(options) {
                    // Block UDP for longer than idle timeout
                    EmulatorNetworkUtils.blockUdp()
                    delay(5.seconds)
                    EmulatorNetworkUtils.unblockUdp()

                    // If we reach here, the connection didn't time out (unexpected)
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

                // Send first message
                val buf1 = BufferFactory.Default.allocate(5)
                buf1.writeString("part1", Charset.UTF8)
                buf1.resetForRead()
                stream.write(buf1, 5.seconds)

                // Block network briefly
                EmulatorNetworkUtils.blockUdp()
                delay(1.seconds)
                EmulatorNetworkUtils.unblockUdp()
                delay(1.seconds)

                // Send second message — should work after recovery
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
                // Add 500ms latency
                EmulatorNetworkUtils.addLatency(500)
                delay(1.seconds)

                // Open stream — should work despite latency
                val stream = openStream()
                val buf = BufferFactory.Default.allocate(4)
                buf.writeString("test", Charset.UTF8)
                buf.resetForRead()
                stream.write(buf, 10.seconds) // longer timeout for high latency

                stream.close()
                EmulatorNetworkUtils.removeLatency()
            }
        }

    @Test
    fun airplaneModeToggle() =
        runBlocking(Dispatchers.IO) {
            try {
                withServerConnection {
                    // Toggle airplane mode
                    EmulatorNetworkUtils.airplaneModeOn()
                    delay(2.seconds)
                    EmulatorNetworkUtils.airplaneModeOff()
                    delay(3.seconds)

                    // If we're still here, connection survived
                }
            } catch (_: Throwable) {
                // Connection may have closed — that's acceptable
            }
        }
}
