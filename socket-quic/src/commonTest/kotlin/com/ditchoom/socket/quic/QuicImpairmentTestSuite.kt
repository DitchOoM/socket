package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **network-impairment** test suite (issue #87, suite #1). Drives deterministic packet loss /
 * reordering / duplication / latency+jitter / burst-blackhole on the QUIC data path and asserts the
 * connection survives and a multi-datagram payload still round-trips byte-for-byte — i.e. QUIC's
 * retransmit / ACK / loss-recovery / reassembly / dedup logic actually works.
 *
 * Same 3-tier shape as [QuicPassiveMigrationTestSuite]: each platform supplies a [testTlsConfig] and a
 * [createImpairingProxy]; the test bodies are inherited, guaranteeing parity across JVM, Linux K/N, and
 * any future common-test platform. (Android's equivalent is a parallel copy in `androidInstrumentedTest`,
 * which can't see `commonTest` — see `AndroidQuicImpairmentTests`.)
 *
 * **Determinism (hard constraint).** No randomness, no `Math.random` (unavailable on K/N), no
 * probabilistic flake-catchers. Impairment is driven purely by a per-direction datagram **index
 * counter** via [ImpairmentPolicy]. The proxy starts in pass-through; the test does one clean echo to
 * prove the handshake + stream are established, then calls [ImpairingProxy.arm] — from that point the
 * policy is applied to the *data phase* only, with indices counted from 0. This separates the variable
 * handshake flight from the impairment under test without parsing packets. The pass/fail *outcome* is
 * deterministic (QUIC always recovers); only latency varies, bounded by a generous `idleTimeout`.
 *
 * **Anti-vacuous guard.** [streamStallsUnderTotalBlackhole] is a real test (not a comment): with the
 * policy dropping everything post-arm, the echo must time out — proving the proxy is the sole path and
 * the suite *can* fail. Combined with the `*Count > 0` assertions (impairment provably fired on the
 * path), this rules out a proxy bug that silently bypasses impairment and lets every positive test pass.
 */
abstract class QuicImpairmentTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Platform-specific impairing proxy (blocking DatagramChannel on JVM/Android, io_uring on Linux K/N). */
    abstract fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val impairOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            // Generous so idle-timeout never races loss/PTO recovery — the impairment is data-phase only.
            idleTimeout = 30.seconds,
        )

    // ---- tests -------------------------------------------------------------------------------------

    /** Drop every 7th post-arm datagram (both directions); QUIC retransmit must still deliver the payload. */
    @Test
    fun streamSurvivesDeterministicLoss() =
        runImpaired(
            policy = { _, index -> if (index % 7 == 6) ImpairAction.Drop else ImpairAction.Forward },
            assertCounters = { proxy -> assertTrue(proxy.droppedCount > 0, "no datagram was dropped — impairment did not fire") },
        )

    /** Hold even-indexed post-arm datagrams and release them after the next one — a structural reorder. */
    @Test
    fun streamSurvivesReordering() =
        runImpaired(
            policy = { _, index -> if (index % 2 == 0) ImpairAction.HoldUntilNext else ImpairAction.Forward },
            assertCounters = { proxy -> assertTrue(proxy.reorderedCount > 0, "nothing was reordered — impairment did not fire") },
        )

    /** Forward every post-arm datagram twice; QUIC must dedup so the payload is delivered exactly once. */
    @Test
    fun streamSurvivesDuplication() =
        runImpaired(
            policy = { _, _ -> ImpairAction.ForwardTwice },
            assertCounters = { proxy -> assertTrue(proxy.duplicatedCount > 0, "nothing was duplicated — impairment did not fire") },
        )

    /** Delay every post-arm datagram by a deterministic base + per-index jitter; payload still round-trips. */
    @Test
    fun streamSurvivesLatencyAndJitter() =
        runImpaired(
            policy = { _, index -> ImpairAction.ForwardAfter(20L + (index % 5) * 10L) },
            assertCounters = { proxy -> assertTrue(proxy.delayedCount > 0, "nothing was delayed — impairment did not fire") },
        )

    /** Drop a contiguous burst of post-arm datagrams once (partial blackhole), then pass; QUIC recovers. */
    @Test
    fun streamSurvivesBurstLossThenRecovery() =
        runImpaired(
            policy = { _, index -> if (index in BURST_START until BURST_START + BURST_SIZE) ImpairAction.Drop else ImpairAction.Forward },
            assertCounters = { proxy -> assertTrue(proxy.droppedCount > 0, "burst was not dropped — impairment did not fire") },
        )

    /**
     * Anti-vacuous guard: drop everything post-arm. The echo of the data payload must time out, proving
     * the proxy carries the only path and the positive assertions are not vacuous. If the proxy ever
     * silently bypassed impairment, this would pass (round-trip) and fail the test.
     */
    @Test
    fun streamStallsUnderTotalBlackhole() =
        runQuicTest {
            wrapTestBody {
                withImpairedServerAndClient(policy = { _, _ -> ImpairAction.Drop }) { stream, proxy ->
                    proxy.arm()
                    val expected = "probe" // small — blackhole only needs the path blocked, not bulk data
                    var timedOut = false
                    try {
                        // After arm() every data datagram is dropped, so the payload can never round-trip.
                        withTimeout(BLACKHOLE_TIMEOUT) { stream.echoExact(expected) }
                    } catch (_: TimeoutCancellationException) {
                        timedOut = true
                    }
                    assertTrue(timedOut, "payload round-tripped under a total blackhole — proxy is not the sole path / impairment bypassed")
                    assertTrue(proxy.droppedCount > 0, "blackhole dropped nothing — impairment did not fire")
                }
            }
        }

    // ---- orchestration -----------------------------------------------------------------------------

    private fun runImpaired(
        policy: ImpairmentPolicy,
        assertCounters: (ImpairingProxy) -> Unit,
    ) = runQuicTest {
        wrapTestBody {
            withImpairedServerAndClient(policy) { stream, proxy ->
                proxy.arm() // data-phase impairment starts now; handshake already completed pass-through
                val expected = payload()
                val echoed = withTimeout(ECHO_TIMEOUT) { stream.echoExact(expected) }
                assertEquals(expected.length, echoed.length, "echoed payload truncated under impairment")
                assertEquals(expected, echoed, "echoed payload corrupted under impairment")
                assertCounters(proxy)
            }
        }
    }

    /**
     * Stands up the in-process echo server, an impairing proxy in front of it, and a client that connects
     * *through* the proxy and opens one stream. Does a clean pass-through warm-up echo (so the handshake +
     * stream are established before any [ImpairingProxy.arm]), then hands the open stream + proxy to [body].
     */
    private suspend fun withImpairedServerAndClient(
        policy: ImpairmentPolicy,
        body: suspend (stream: QuicByteStream, proxy: ImpairingProxy) -> Unit,
    ) = coroutineScope {
        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = impairOptions) {
            // Echo loop: mirror every chunk back until the stream ends.
            val serverJob =
                launch {
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

            val proxy = createImpairingProxy(port, policy)
            val done = CompletableDeferred<Unit>()

            val clientJob =
                launch {
                    try {
                        withQuicConnection("127.0.0.1", proxy.proxyPort, impairOptions, timeout = 15.seconds) {
                            val stream = openStream()
                            // Pass-through warm-up: proves handshake + stream are up before impairment arms.
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

    /**
     * Write [payload], then read exactly [payload].length bytes back, returning what came back. The
     * connection's initial flow-control windows comfortably exceed [PAYLOAD_SIZE], so `write` buffers the
     * whole payload into quiche and returns without waiting on the reader — no sequential-echo deadlock at
     * these sizes. The write/read buffers are freed (the suite also runs on K/N, where they are native).
     */
    private suspend fun QuicByteStream.echoExact(payload: String): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        try {
            write(out, ECHO_TIMEOUT)
        } finally {
            out.freeNativeMemory()
        }
        return readExactly(payload.length, ECHO_TIMEOUT)
    }

    /** Accumulate stream reads until [total] characters have arrived (ASCII-only payloads never split a codepoint). */
    private suspend fun QuicByteStream.readExactly(
        total: Int,
        timeout: Duration,
    ): String {
        val sb = StringBuilder(total)
        while (sb.length < total) {
            val r = read(timeout)
            if (r is ReadResult.Data) {
                sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                r.buffer.freeIfNeeded() // read transfers buffer ownership to us (see QuicheStreamAdapter)
            } else {
                break
            }
        }
        return sb.toString()
    }

    private fun payload(): String = deterministicAscii(PAYLOAD_SIZE)

    companion object {
        /** ~16 KB ⇒ ≈12–14 datagrams per direction, so impairment lands on real stream data, not just ACKs. */
        private const val PAYLOAD_SIZE = 8 * 1024
        private const val WARMUP = "warmup"
        private const val BURST_START = 3
        private const val BURST_SIZE = 5
        private val ECHO_TIMEOUT = 8.seconds
        private val BLACKHOLE_TIMEOUT = 4.seconds

        /** Deterministic single-byte-UTF8 payload: `A B C … Z A B …` so chunk boundaries never split a codepoint. */
        private fun deterministicAscii(length: Int): String =
            buildString(length) {
                for (i in 0 until length) append('A' + (i % 26))
            }
    }
}

/** Datagram direction through the proxy, so a policy can impair one side independently if it chooses. */
enum class ImpairDirection { ClientToServer, ServerToClient }

/** What the proxy should do with a single datagram. Deterministic; chosen purely from a per-direction index. */
sealed interface ImpairAction {
    /** Forward once, immediately (the no-op). */
    data object Forward : ImpairAction

    /** Drop — never forwarded. */
    data object Drop : ImpairAction

    /** Forward twice in a row (duplication). */
    data object ForwardTwice : ImpairAction

    /** Forward once after [delayMs] (latency / jitter). Requires copying the datagram out of the recv buffer. */
    data class ForwardAfter(
        val delayMs: Long,
    ) : ImpairAction

    /** Hold this datagram and release it *after* the next datagram in the same direction (structural reorder). */
    data object HoldUntilNext : ImpairAction
}

/** Pure, stateless mapping `(direction, index) → action`. The proxy owns the indices and the counters. */
fun interface ImpairmentPolicy {
    fun actionFor(
        direction: ImpairDirection,
        index: Int,
    ): ImpairAction
}

/**
 * A userspace UDP forwarder that applies a deterministic [ImpairmentPolicy] to the QUIC data path.
 * Client ↔ [proxyPort] ↔ server. Starts in pass-through; [arm] switches the policy on (indices reset to 0)
 * so the handshake completes cleanly first. Each platform implements it over its native UDP API.
 */
interface ImpairingProxy {
    /** The local port the client connects to (the proxy's client-facing socket). */
    val proxyPort: Int

    /** Switch from pass-through to applying the policy; resets per-direction indices to 0. */
    fun arm()

    val droppedCount: Int
    val duplicatedCount: Int
    val delayedCount: Int
    val reorderedCount: Int

    /** Stop the pump loops and release all sockets/resources/held buffers. */
    suspend fun close()
}
