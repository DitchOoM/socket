@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Option 2 of P7 (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §6.1, remaining item b): proof that wiring the
 * caller-clock into the Tier-A sim actually drives real libquiche's internal clock through the
 * **production seam** — `SimClock.quicheTime()` → [CallerClockQuicheApi] decorator →
 * `quiche_set_virtual_time_nanos` — not just the raw FFI that [JvmCallerClockTests] already covers.
 *
 * A [SimClock] backed by a standalone [TestCoroutineScheduler] plays the role of the Tier-A virtual
 * clock. The real quiche api is wrapped in [CallerClockQuicheApi] exactly as [QuicheDriver] wraps it
 * when `clock.quicheTime() is DriverTime.Virtual`, so every `connTimeout`/`connOnTimeout`/`connIsTimedOut`
 * call pins libquiche's per-thread clock to the scheduler's virtual time first — no raw
 * `setThreadVirtualTimeNanos` in the steady-state loop.
 *
 * The assertion is **bit-exactness across two independent runs**: each run gets a fresh scheduler
 * starting at virtual t0, drives the identical virtual schedule, and records a *structural* trace —
 * the sequence of `(virtualMs, connTimeout nanos, isTimedOut)` observations plus the padded Initial
 * length. These fields are clock-driven and RNG-independent (packet-number/CID randomness changes the
 * encrypted *bytes*, which the seeded-RAND_METHOD follow-on handles, not these timer values), so with
 * the caller-clock they are identical run-to-run. The trace is also asserted non-trivial — the timeout
 * strictly shrinks under advancing virtual time and the connection finally idle-times-out on the
 * virtual clock alone — which is what proves the scheduler's time genuinely reached quiche rather than
 * the values being frozen.
 */
class JvmCallerClockSimTests {
    @Test
    fun simClockDrivesQuiche_bitExactStructuralTraceAcrossRuns() {
        val traceA =
            try {
                driveStructuralTrace()
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
                return
            }
        val traceB = driveStructuralTrace()

        assertEquals(
            traceA,
            traceB,
            "SimClock-driven quiche must produce a bit-exact structural trace across two runs — " +
                "if these differ the caller-clock seam is leaking real wall-clock time into quiche's timers",
        )

        // Non-triviality: the trace must actually move with the virtual clock, else "equal" would be
        // vacuously true against a frozen (un-driven) clock.
        assertTrue(traceA.first().startsWith("initial len="), "expected the padded Initial length first")
        val timeouts =
            traceA
                .filter { it.startsWith("tick=") }
                .map { it.substringAfter("timeout=").toLong() }
        assertTrue(timeouts.size >= 3, "expected several timer observations, got ${timeouts.size}")
        assertTrue(
            timeouts.zipWithNext().all { (a, b) -> b < a },
            "the armed timer must strictly shrink as virtual time advances — proves the scheduler's clock " +
                "reaches quiche; timeouts=$timeouts",
        )
        assertEquals(
            "timedOut=true",
            traceA.last(),
            "the connection must idle-time-out on the virtual clock alone (no real sleep) — trace=$traceA",
        )
    }

    /**
     * One deterministic run: connect a real quiche client at virtual t0, step the [SimClock]'s scheduler
     * forward in fixed 50 ms virtual ticks reading quiche's armed timer through the [CallerClockQuicheApi]
     * wrapper at each tick (the timer shrinks exactly with the scheduler — proving the clock reaches
     * quiche), then jump the virtual clock far past every timer and fire `on_timeout` once to observe the
     * idle-timeout close. The ticks stop **before** the first loss/PTO timer is due (≈ one initial-RTT
     * PTO ≈ 999 ms with the 1 s idle config, since RFC 9000 §10.1 makes the effective idle deadline
     * `max(idle, 3·PTO)`), so the loop never mutates recovery state and the shrink stays a clean ramp.
     * Returns the structural trace.
     */
    private fun driveStructuralTrace(): List<String> {
        val rawApi = loadQuicheApi()
        val bufferFactory = BufferFactory.network()
        val scheduler = TestCoroutineScheduler()
        val clock = SimClock(scheduler)
        // The exact wrapper QuicheDriver installs for a Virtual clock — steady-state calls auto-pin.
        val api: QuicheApi = CallerClockQuicheApi(rawApi, clock)

        val peerAddr = InetSocketAddress("127.0.0.1", 4433)
        val localAddr = InetSocketAddress("127.0.0.1", 55555)

        val config = rawApi.configNew(QUICHE_PROTOCOL_VERSION)
        val alpn = encodeAlpnList(listOf("test"), bufferFactory)
        rawApi.configSetApplicationProtos(config, alpn.nativeMemoryAccess!!.nativeAddress, alpn.remaining())
        rawApi.configSetMaxIdleTimeout(config, 1000L) // 1s idle
        alpn.freeNativeMemory()

        val serverName = "localhost"
        val serverNameBuf = bufferFactory.allocate(serverName.length + 1)
        serverNameBuf.writeString(serverName, Charset.UTF8)
        serverNameBuf.writeByte(0)
        serverNameBuf.resetForRead()
        val scid = generateScid(bufferFactory, Random(42))
        val connectPeer = peerAddr.toNativeSockAddr(bufferFactory)
        val connectLocal = localAddr.toNativeSockAddr(bufferFactory)

        // Pin the clock for the one call the wrapper does NOT cover — connect, which reads quiche's
        // "created" instant. In production this happens in the backend before the driver owns the conn;
        // here we pin it from the same SimClock so "created" is virtual t0 (scheduler at 0).
        val virtualTime = clock.quicheTime()
        check(virtualTime is DriverTime.Virtual) { "SimClock must report Virtual time" }
        rawApi.setThreadVirtualTimeNanos(virtualTime.nanos)
        val conn =
            rawApi.connect(
                serverNameBuf.nativeMemoryAccess!!.nativeAddress,
                serverName.length,
                scid.nativeMemoryAccess!!.nativeAddress,
                QUIC_MAX_CONN_ID_LEN,
                connectLocal.address,
                connectLocal.length,
                connectPeer.address,
                connectPeer.length,
                config,
            )
        rawApi.configFree(config)
        serverNameBuf.freeNativeMemory()
        scid.freeNativeMemory()
        connectPeer.free()
        connectLocal.free()

        val sendInfo = api.sendInfoNew()
        val out = bufferFactory.allocate(1350)
        val trace = mutableListOf<String>()
        try {
            // Flush the Initial at virtual t0 through the wrapper → quiche arms loss/idle off t0.
            val sent = api.connSend(conn, out.nativeMemoryAccess!!.nativeAddress, 1350, sendInfo)
            trace.add("initial len=$sent")

            // Observe the armed timer shrink exactly with virtual time, stopping one tick before the
            // first timer is due so recovery state is untouched (ticks 0..18 → 0..900 ms).
            repeat(19) { tick ->
                val vMs = scheduler.currentTime
                val d = api.connTimeout(conn)
                trace.add("tick=$tick t=${vMs}ms timeout=${d?.inWholeNanoseconds ?: -1}")
                scheduler.advanceTimeBy(50.milliseconds)
            }

            // Jump the virtual clock far past every timer (idle + 3·PTO) and fire on_timeout once — the
            // proven idle-timeout path (mirrors *CallerClockTests): a full idle close on the virtual
            // clock alone, no real sleep.
            scheduler.advanceTimeBy(60_000.milliseconds)
            val dFinal = api.connTimeout(conn)
            trace.add("final t=${scheduler.currentTime}ms timeout=${dFinal?.inWholeNanoseconds ?: -1}")
            api.connOnTimeout(conn)
            trace.add("timedOut=${api.connIsTimedOut(conn)}")
        } finally {
            rawApi.clearThreadVirtualTime()
            out.freeNativeMemory()
            api.sendInfoFree(sendInfo)
            api.connFree(conn)
        }
        return trace
    }
}
