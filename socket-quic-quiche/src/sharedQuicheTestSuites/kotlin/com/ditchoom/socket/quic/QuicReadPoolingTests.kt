@file:OptIn(ExperimentalDatagramApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.counting
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for QUIC read-path buffer recycling — the quiche-layer companion of the TCP
 * `ReadBufferPoolingTest`. [DriverStreamAdapter.streamRead] used to allocate a fresh 64 KB buffer
 * from the leaf factory on EVERY stream read (and [DriverDatagramAdapter.receive] a fresh
 * 1350-byte buffer per datagram); under the default collector-owned factory those accumulate without
 * bound under high read throughput. Reads now draw from the driver's per-connection
 * [QuicheDriver.streamReadPool] / [QuicheDriver.recvBufPool], and the consumer's `freeNativeMemory()`
 * recycles each buffer back to its pool.
 *
 * Recycling is what this suite measures; it is **not** a leak guard, and #538 is what the difference
 * costs. The pool only recycles what the consumer releases, and the consumer that never does is worse
 * off with the pool than without it. `QuicStreamReadMemorySoakTests` measures that half — the process's
 * native footprint over thousands of real reads — where this one measures allocation counts.
 *
 * Deterministic: a counting leaf factory records how many buffers the driver actually allocates.
 * With pooling that is a handful (driver scratch + pool misses); pre-fix it was ≈ one per read.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, so a `commonTest` home
 * covered every platform *except* the one that ships this backend to users: Android is the only target
 * that runs quiche over JNI, and it is where issue #393 was found in the field. This directory is
 * `srcDir`'d into both source sets, so the same source runs unchanged on jvm/apple/linux **and** on a
 * real device — the move adds the lane that was missing and takes none away. See DitchOoM/socket#390.
 */
class QuicReadPoolingTests {
    private fun driverWith(
        api: StubQuicheApi,
        factory: BufferFactory,
        capture: TraceCapture = TraceCapture.Off,
    ): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = factory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
            capture = capture,
        )

    private suspend fun openStream(driver: QuicheDriver): StreamSlot {
        val deferred = CompletableDeferred<StreamSlot>()
        driver.commands.send(QuicheCmd.OpenStream(deferred))
        return withTimeout(2.seconds) { deferred.await() }
    }

    // The native-memory leaf factories QUIC accepts on THIS platform (quicBufferFactory() rejects
    // heap factories at setup): network() is the production default (what quicBufferFactory()
    // resolves to when the caller doesn't override); Default is a valid collector-owned override only
    // where it allocates native buffers (JVM Arena, Apple NSMutableData) — on Linux it is a managed
    // ByteArrayBuffer, so probe like requireNativeMemory().
    private val leafFactories: List<Pair<String, BufferFactory>>
        get() =
            buildList {
                add("network" to BufferFactory.network())
                val probe = BufferFactory.Default.allocate(1)
                if (probe.nativeMemoryAccess != null) add("default" to BufferFactory.Default)
                probe.freeIfNeeded()
            }

    @Test
    fun streamReadBuffersAreRecycledNotAllocatedPerRead() =
        runQuicTest {
            for ((name, leaf) in leafFactories) {
                val counting = leaf.counting()
                val api = StubQuicheApi().apply { streamRecvResult = StreamRecvResult.Data(1024, false) }
                val driver = driverWith(api, counting)
                driver.start(this)
                try {
                    val slot = openStream(driver)
                    val stream = QuicheStreamByteStream(slot.id, DriverStreamAdapter(driver, slot), driver.streamReadPool)
                    repeat(300) {
                        // The stub holds bytes for every read, as a sweep would have announced.
                        slot.announce()
                        val result = withTimeout(2.seconds) { stream.read(2.seconds) }
                        assertIs<ReadResult.Data>(result, "unexpected non-data read: $result")
                        // The consumer hands ownership back after consuming — recycles to the pool.
                        result.buffer.freeIfNeeded()
                    }
                } finally {
                    driver.destroy()
                }
                val allocations = counting.allocationCount
                // Pooling ⇒ the leaf factory is hit only for driver scratch buffers + pool misses,
                // NOT once per read (pre-fix: 300+ allocations here).
                assertTrue(
                    allocations < 20,
                    "[$name] stream read buffers should be pooled/recycled: expected < 20 leaf allocations, got $allocations over 300 reads",
                )
            }
        }

    /**
     * The same recycling when the loop serves the reads: each read parks, and a turn of the loop that finds
     * the stream readable hands it its bytes (`handToWaitingRead`) instead of the read asking. Nothing
     * here signals the slot by hand: the stub reports the stream readable, as quiche does while it holds
     * bytes, and the turns are commands.
     */
    @Test
    fun streamReadBuffersTheLoopHandsOffAreRecycledNotAllocatedPerRead() =
        runQuicTest {
            for ((name, leaf) in leafFactories) {
                val counting = leaf.counting()
                val api = StubQuicheApi().apply { streamRecvResult = StreamRecvResult.Data(1024, false) }
                // The flight recorder names the loop's and the reader's last steps if a read is never served.
                val driver = driverWith(api, counting, TraceCapture.On(QuicTraceRecorder(TraceSink { })))
                // The recorder's state collectors end with the scope the driver starts in, not with destroy().
                val driverScope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
                driver.start(driverScope)
                try {
                    val slot = openStream(driver)
                    val stream = QuicheStreamByteStream(slot.id, DriverStreamAdapter(driver, slot), driver.streamReadPool)
                    repeat(300) { iteration ->
                        try {
                            val read = async { stream.read(2.seconds) }
                            // Turns of the loop until the parked read has been served; the stream is readable.
                            api.readableWhileRunning.store(listOf(slot.id.id))
                            var turns = 0
                            while (!read.isCompleted) {
                                if (++turns > MAX_TURNS_PER_READ) fail("not served in $MAX_TURNS_PER_READ turns")
                                val turn = CompletableDeferred<QuicStatsSnapshot>()
                                driver.commands.send(QuicheCmd.Stats(turn))
                                withTimeout(2.seconds) { turn.await() }
                                yield()
                            }
                            api.readableWhileRunning.store(emptyList())
                            val result = read.await()
                            assertIs<ReadResult.Data>(result, "unexpected non-data read: $result")
                            result.buffer.freeIfNeeded()
                        } catch (failure: Throwable) {
                            throw AssertionError("[$name] read $iteration: $failure. ${driver.flightRecording().rendered()}", failure)
                        }
                    }
                } finally {
                    // The stub answers every stream_recv with bytes; while it reports the stream readable, the
                    // teardown drain would take them until memory runs out and hide whatever failed here.
                    api.readableWhileRunning.store(emptyList())
                    driver.destroy()
                    driverScope.cancel()
                }
                val allocations = counting.allocationCount
                assertTrue(
                    allocations < 20,
                    "[$name] hand-off read buffers should be pooled/recycled: expected < 20 leaf allocations, got $allocations over 300 reads",
                )
            }
        }

    @Test
    fun datagramReceiveBuffersAreRecycledNotAllocatedPerReceive() =
        runQuicTest {
            for ((name, leaf) in leafFactories) {
                val counting = leaf.counting()
                val api = StubQuicheApi().apply { dgramRecvResult = StreamRecvResult.Data(3, false) }
                val driver = driverWith(api, counting)
                val adapter = DriverDatagramAdapter(driver, SocketAddress.ofLiteral("127.0.0.1", 4433))
                driver.start(this)
                try {
                    repeat(300) {
                        val result = withTimeout(2.seconds) { adapter.receive() }
                        assertIs<DatagramReadResult.Received>(result)
                        result.datagram.payload.freeIfNeeded()
                    }
                } finally {
                    driver.destroy()
                }
                val allocations = counting.allocationCount
                assertTrue(
                    allocations < 20,
                    "[$name] datagram receive buffers should be pooled/recycled: expected < 20 leaf allocations, got $allocations over 300 receives",
                )
            }
        }

    private fun FlightRecording.rendered(): String =
        when (this) {
            FlightRecording.Off -> "flight recorder off"
            is FlightRecording.Recorded -> render()
        }

    private companion object {
        /** A parked read is served by the first turn that sees it parked; a few turns cover the race with the park. */
        const val MAX_TURNS_PER_READ = 1_000
    }
}
