@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    com.ditchoom.buffer.flow.ExperimentalDatagramApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_idle_timeout
import com.ditchoom.socket.quic.quiche.quiche_connect
import com.ditchoom.socket.quic.sim.SimClock
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.appleSockAddrLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import platform.posix.sockaddr
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Apple (macOS / iOS-sim) mirror of [LinuxCallerClockSimTests] / [JvmCallerClockSimTests]: Option 2 of
 * P7 (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §6.1, remaining item b) on the Apple cinterop backend. Proves
 * the **production seam** — `SimClock.quicheTime()` → [CallerClockQuicheApi] decorator →
 * `quiche_set_virtual_time_nanos` — drives the Apple-built libquiche.a's internal clock, not just the
 * raw FFI that [AppleCallerClockTests] already covers.
 *
 * A [SimClock] on a standalone [TestCoroutineScheduler] plays the Tier-A virtual clock; the real
 * [CinteropQuicheApi] is wrapped exactly as [QuicheDriver] wraps it for a `DriverTime.Virtual` clock.
 * The assertion is bit-exactness of a *structural* (clock-driven, RNG-independent) trace across two
 * independent runs, the timer strictly shrinking under advancing virtual time, and a final idle-timeout
 * on the virtual clock alone. See [JvmCallerClockSimTests] for the full rationale.
 */
class AppleCallerClockSimTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun simClockDrivesQuiche_bitExactStructuralTraceAcrossRuns() =
        runBlocking {
            val traceA = driveStructuralTrace()
            val traceB = driveStructuralTrace()

            assertEquals(
                traceA,
                traceB,
                "SimClock-driven quiche must produce a bit-exact structural trace across two runs — " +
                    "if these differ the caller-clock seam is leaking real wall-clock time into quiche's timers",
            )
            assertTrue(traceA.first().startsWith("initial len="), "expected the padded Initial length first")
            val timeouts =
                traceA
                    .filter { it.startsWith("tick=") }
                    .map { it.substringAfter("timeout=").toLong() }
            assertTrue(timeouts.size >= 3, "expected several timer observations, got ${timeouts.size}")
            assertTrue(
                timeouts.zipWithNext().all { (a, b) -> b < a },
                "the armed timer must strictly shrink as virtual time advances — proves the scheduler's " +
                    "clock reaches quiche; timeouts=$timeouts",
            )
            assertEquals(
                "timedOut=true",
                traceA.last(),
                "the connection must idle-time-out on the virtual clock alone (no real sleep) — trace=$traceA",
            )
        }

    /** One deterministic run — see [JvmCallerClockSimTests.driveStructuralTrace] for the scenario shape. */
    private suspend fun driveStructuralTrace(): List<String> {
        val rawApi = CinteropQuicheApi
        val scheduler = TestCoroutineScheduler()
        val clock = SimClock(scheduler)
        val api: QuicheApi = CallerClockQuicheApi(rawApi, clock)

        val codec = SocketAddressCodec(appleSockAddrLayout)
        val peer = UdpSocket.resolve("127.0.0.1", 4433)
        val local = UdpSocket.resolve("127.0.0.1", 55555)
        val peerSA = codec.encodeToNative(peer, bufferFactory)
        val localSA = codec.encodeToNative(local, bufferFactory)
        val scid = generateScid(bufferFactory, Random(42))
        val scidPtr = scid.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
        val alpn = encodeAlpnList(listOf("test"), bufferFactory)
        val alpnPtr = alpn.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

        val config = quiche_config_new(QUICHE_PROTOCOL_VERSION.convert()) ?: error("quiche_config_new failed")
        quiche_config_set_application_protos(config, alpnPtr, alpn.remaining().convert())
        quiche_config_set_max_idle_timeout(config, 1000.convert()) // 1s idle
        alpn.freeNativeMemory()

        // Pin the clock for the one call the wrapper does not cover — connect — from the same SimClock.
        val virtualTime = clock.quicheTime()
        check(virtualTime is DriverTime.Virtual) { "SimClock must report Virtual time" }
        rawApi.setThreadVirtualTimeNanos(virtualTime.nanos)
        val connPtr =
            quiche_connect(
                "localhost",
                scidPtr,
                20.convert(),
                localSA.address.toCPointer<sockaddr>(),
                localSA.length.convert(),
                peerSA.address.toCPointer<sockaddr>(),
                peerSA.length.convert(),
                config,
            ) ?: error("quiche_connect failed")
        quiche_config_free(config)
        val conn = QuicheConn(connPtr.rawValue.toLong())
        val sendInfo = api.sendInfoNew()
        val trace = mutableListOf<String>()
        try {
            val sent =
                memScoped {
                    val out = allocArray<ByteVar>(1350)
                    api.connSend(conn, out.rawValue.toLong(), 1350, sendInfo)
                }
            trace.add("initial len=$sent")

            repeat(19) { tick ->
                val vMs = scheduler.currentTime
                val d = api.connTimeout(conn)
                trace.add("tick=$tick t=${vMs}ms timeout=${d?.inWholeNanoseconds ?: -1}")
                scheduler.advanceTimeBy(50.milliseconds)
            }

            scheduler.advanceTimeBy(60_000.milliseconds)
            val dFinal = api.connTimeout(conn)
            trace.add("final t=${scheduler.currentTime}ms timeout=${dFinal?.inWholeNanoseconds ?: -1}")
            api.connOnTimeout(conn)
            trace.add("timedOut=${api.connIsTimedOut(conn)}")
        } finally {
            api.clearThreadVirtualTime()
            api.sendInfoFree(sendInfo)
            api.connFree(conn)
            peerSA.free()
            localSA.free()
            scid.freeNativeMemory()
        }
        return trace
    }
}
