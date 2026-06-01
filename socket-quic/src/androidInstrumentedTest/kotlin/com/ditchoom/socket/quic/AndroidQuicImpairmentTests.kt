package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Network-impairment tests on Android — the Android port of the JVM/Linux [QuicImpairmentTestSuite]
 * (issue #87, suite #1). `androidInstrumentedTest` is a separate on-device compilation that doesn't see
 * `commonTest`, so this is a self-contained parallel copy (same reason
 * [AndroidQuicPassiveMigrationTests] duplicates the passive suite). The policy, proxy, and the six test
 * bodies are inlined; they mirror the shared suite exactly.
 *
 * Both ends run in one Android process via [withQuicServer]. A userspace [DatagramChannelImpairingProxy]
 * sits between client and the in-process server and applies deterministic loss / reordering /
 * duplication / latency+jitter / blackhole to the data path once [DatagramChannelImpairingProxy.arm]ed;
 * we assert the connection survives and a multi-datagram payload still round-trips byte-for-byte.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicImpairmentTests {
    // ---- inlined policy (commonTest's ImpairAction/ImpairmentPolicy/ImpairDirection) ----------------

    private enum class Direction { ClientToServer, ServerToClient }

    private sealed interface Action {
        data object Forward : Action

        data object Drop : Action

        data object ForwardTwice : Action

        data class ForwardAfter(
            val delayMs: Long,
        ) : Action

        data object HoldUntilNext : Action
    }

    private fun interface Policy {
        fun actionFor(
            direction: Direction,
            index: Int,
        ): Action
    }

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
        )

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    // ---- tests -------------------------------------------------------------------------------------

    @Test
    fun streamSurvivesDeterministicLoss() =
        runImpaired(
            policy = { _, index -> if (index % 7 == 6) Action.Drop else Action.Forward },
            assertCounters = { proxy -> assertTrue(proxy.droppedCount > 0, "no datagram was dropped — impairment did not fire") },
        )

    @Test
    fun streamSurvivesReordering() =
        runImpaired(
            policy = { _, index -> if (index % 2 == 0) Action.HoldUntilNext else Action.Forward },
            assertCounters = { proxy -> assertTrue(proxy.reorderedCount > 0, "nothing was reordered — impairment did not fire") },
        )

    @Test
    fun streamSurvivesDuplication() =
        runImpaired(
            policy = { _, _ -> Action.ForwardTwice },
            assertCounters = { proxy -> assertTrue(proxy.duplicatedCount > 0, "nothing was duplicated — impairment did not fire") },
        )

    @Test
    fun streamSurvivesLatencyAndJitter() =
        runImpaired(
            policy = { _, index -> Action.ForwardAfter(20L + (index % 5) * 10L) },
            assertCounters = { proxy -> assertTrue(proxy.delayedCount > 0, "nothing was delayed — impairment did not fire") },
        )

    @Test
    fun streamSurvivesBurstLossThenRecovery() =
        runImpaired(
            policy = { _, index -> if (index in BURST_START until BURST_START + BURST_SIZE) Action.Drop else Action.Forward },
            assertCounters = { proxy -> assertTrue(proxy.droppedCount > 0, "burst was not dropped — impairment did not fire") },
        )

    @Test
    fun streamStallsUnderTotalBlackhole() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    withImpairedServerAndClient(policy = { _, _ -> Action.Drop }) { stream, proxy ->
                        proxy.arm()
                        var timedOut = false
                        try {
                            withTimeout(BLACKHOLE_TIMEOUT) { stream.echoExact("probe") }
                        } catch (_: TimeoutCancellationException) {
                            timedOut = true
                        }
                        assertTrue(
                            timedOut,
                            "payload round-tripped under a total blackhole — proxy is not the sole path / impairment bypassed",
                        )
                        assertTrue(proxy.droppedCount > 0, "blackhole dropped nothing — impairment did not fire")
                    }
                }
            }
        }

    // ---- orchestration -----------------------------------------------------------------------------

    private fun runImpaired(
        policy: Policy,
        assertCounters: (DatagramChannelImpairingProxy) -> Unit,
    ) = runBlocking(Dispatchers.IO) {
        skipOnMissingNativeLib {
            withTimeout(25.seconds) {
                withImpairedServerAndClient(policy) { stream, proxy ->
                    proxy.arm()
                    val expected = deterministicAscii(PAYLOAD_SIZE)
                    val echoed = withTimeout(ECHO_TIMEOUT) { stream.echoExact(expected) }
                    assertEquals(expected.length, echoed.length, "echoed payload truncated under impairment")
                    assertEquals(expected, echoed, "echoed payload corrupted under impairment")
                    assertCounters(proxy)
                }
            }
        }
    }

    private suspend fun withImpairedServerAndClient(
        policy: Policy,
        body: suspend (stream: QuicByteStream, proxy: DatagramChannelImpairingProxy) -> Unit,
    ) = coroutineScope {
        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
            val serverJob =
                launch(Dispatchers.IO) {
                    connections {
                        val stream = acceptStream()
                        while (true) {
                            val data = stream.read(15.seconds)
                            if (data is ReadResult.Data) {
                                stream.write(data.buffer, 10.seconds)
                            } else {
                                break
                            }
                        }
                        stream.close()
                    }
                }

            val proxy = DatagramChannelImpairingProxy(port, policy)
            val done = CompletableDeferred<Unit>()

            val clientJob =
                launch(Dispatchers.IO) {
                    try {
                        withQuicConnection("127.0.0.1", proxy.proxyPort, testQuicOptions, timeout = 15.seconds) {
                            val stream = openStream()
                            assertEquals(WARMUP, stream.echoExact(WARMUP), "warm-up echo failed before impairment")
                            body(stream, proxy)
                            stream.close()
                        }
                        done.complete(Unit)
                    } catch (t: Throwable) {
                        done.completeExceptionally(t)
                    }
                }

            try {
                done.await()
            } finally {
                clientJob.cancel()
                serverJob.cancel()
                proxy.close()
            }
        }
    }

    private suspend fun QuicByteStream.echoExact(payload: String): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 10.seconds)
        return readExactly(payload.length)
    }

    private suspend fun QuicByteStream.readExactly(total: Int): String {
        val sb = StringBuilder(total)
        while (sb.length < total) {
            val r = read(10.seconds)
            if (r is ReadResult.Data) {
                sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
            } else {
                break
            }
        }
        return sb.toString()
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    /**
     * Userspace UDP forwarder that applies [policy] to the QUIC data path once [arm]ed. Two daemon
     * threads pump each direction with blocking [DatagramChannel]s; delayed/held datagrams are copied
     * out of the reused recv buffer before the next receive overwrites it. Test-only; `ByteBuffer` /
     * `ByteArray` are fine. Mirrors the JVM `DatagramChannelImpairingProxy`.
     */
    private class DatagramChannelImpairingProxy(
        serverPort: Int,
        private val policy: Policy,
    ) {
        private val clientChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val proxyPort: Int = (clientChannel.localAddress as InetSocketAddress).port
        private val upstream = DatagramChannel.open().apply { connect(InetSocketAddress("127.0.0.1", serverPort)) }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "impair-sched").apply { isDaemon = true } }

        @Volatile private var clientAddr: SocketAddress? = null

        @Volatile private var running = true

        @Volatile private var armed = false

        private val dropped = AtomicInteger(0)
        private val duplicated = AtomicInteger(0)
        private val delayed = AtomicInteger(0)
        private val reordered = AtomicInteger(0)

        val droppedCount get() = dropped.get()
        val duplicatedCount get() = duplicated.get()
        val delayedCount get() = delayed.get()
        val reorderedCount get() = reordered.get()

        fun arm() {
            armed = true
        }

        private inner class DirectionPump(
            private val direction: Direction,
            private val emitLive: (ByteBuffer) -> Unit,
            private val emitBytes: (ByteArray) -> Unit,
        ) {
            private val index = AtomicInteger(0)

            @Volatile private var held: ByteArray? = null

            fun handle(
                buf: ByteBuffer,
                n: Int,
            ) {
                if (!armed) {
                    emitLive(buf)
                    return
                }
                val toRelease = held
                held = null
                when (val action = policy.actionFor(direction, index.getAndIncrement())) {
                    Action.Forward -> emitLive(buf)
                    Action.Drop -> dropped.incrementAndGet()
                    Action.ForwardTwice -> {
                        emitLive(buf)
                        buf.rewind()
                        emitLive(buf)
                        duplicated.incrementAndGet()
                    }
                    is Action.ForwardAfter -> {
                        val copy = buf.toBytes(n)
                        scheduler.schedule({ emitBytes(copy) }, action.delayMs, TimeUnit.MILLISECONDS)
                        delayed.incrementAndGet()
                    }
                    Action.HoldUntilNext -> {
                        held = buf.toBytes(n)
                        reordered.incrementAndGet()
                    }
                }
                if (toRelease != null) emitBytes(toRelease)
            }

            private fun ByteBuffer.toBytes(n: Int): ByteArray {
                val a = ByteArray(n)
                duplicate().get(a)
                return a
            }
        }

        private fun sendUpstreamLive(b: ByteBuffer) = guarded { upstream.write(b) }

        private fun sendUpstreamBytes(a: ByteArray) = guarded { upstream.write(ByteBuffer.wrap(a)) }

        private fun sendClientLive(b: ByteBuffer) = guarded { clientAddr?.let { clientChannel.send(b, it) } }

        private fun sendClientBytes(a: ByteArray) = guarded { clientAddr?.let { clientChannel.send(ByteBuffer.wrap(a), it) } }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (_: Exception) {
                // Best-effort forward — a closed channel during teardown is expected.
            }
        }

        private val c2sPump = DirectionPump(Direction.ClientToServer, ::sendUpstreamLive, ::sendUpstreamBytes)
        private val s2cPump = DirectionPump(Direction.ServerToClient, ::sendClientLive, ::sendClientBytes)

        private val clientToServer =
            thread(isDaemon = true, name = "impair-c2s") {
                val buf = ByteBuffer.allocate(MAX_DATAGRAM)
                while (running) {
                    try {
                        buf.clear()
                        val from = clientChannel.receive(buf) ?: continue
                        clientAddr = from
                        buf.flip()
                        c2sPump.handle(buf, buf.remaining())
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }

        private val serverToClient =
            thread(isDaemon = true, name = "impair-s2c") {
                val buf = ByteBuffer.allocate(MAX_DATAGRAM)
                while (running) {
                    try {
                        buf.clear()
                        val n = upstream.read(buf)
                        if (n > 0) {
                            buf.flip()
                            s2cPump.handle(buf, n)
                        }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }

        fun close() {
            running = false
            scheduler.shutdownNow()
            clientChannel.close()
            upstream.close()
            clientToServer.interrupt()
            serverToClient.interrupt()
        }
    }

    private companion object {
        private const val PAYLOAD_SIZE = 8 * 1024
        private const val MAX_DATAGRAM = 2048
        private const val WARMUP = "warmup"
        private const val BURST_START = 3
        private const val BURST_SIZE = 5
        private val ECHO_TIMEOUT = 8.seconds
        private val BLACKHOLE_TIMEOUT = 4.seconds

        private fun deterministicAscii(length: Int): String =
            buildString(length) {
                for (i in 0 until length) append('A' + (i % 26))
            }
    }
}
