package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.nativeMemoryAccess
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * JVM mirror of `LinuxCallerClockTests` / `AppleCallerClockTests`: proof that the caller-clock source
 * patch (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §6.1) makes the JVM-linked libquiche's **internal** clock
 * caller-driven through BOTH JVM backends. The Kotlin seam compiles everywhere, but only the K/N
 * cinterop path was exercised against a patched native library; this validates the JVM FFI surfaces:
 *
 *  - default `:socket-quic-quiche:jvmTest` runs the **JNI** backend (`JniQuicheApi` → `quiche_jni.c`
 *    shim → `quiche_set_virtual_time_nanos`);
 *  - `-PquicheJvmBackend=ffm` runs the **FFM** backend (`FfmQuicheApi` Panama downcall into the pure
 *    `libquiche.so`), the production JDK 21+ path.
 *
 * Same discriminating assertion as the other backends: arm quiche's timers with one `send()`, advance
 * the connection's own clock purely via [QuicheApi.setThreadVirtualTimeNanos], and assert [connTimeout]
 * **shrinks** (would stay ~constant on an unpatched lib whose clock is real `Instant::now()`), then
 * idle-times-out on the virtual clock alone — no real sleep, no socket I/O, no live peer. Skips (JUnit
 * assumption) when the bundled native lib is absent, the standard quiche-jvmTest discipline.
 */
class JvmCallerClockTests {
    @Test
    fun virtualTimeDrivesQuicheInternalClock() {
        try {
            drive()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    private fun drive() {
        val api = loadQuicheApi()
        val bufferFactory = BufferFactory.network()

        // Fake-but-valid sockaddrs: quiche only seeds its path state with these; no OS socket exists.
        val peerAddr = InetSocketAddress("127.0.0.1", 4433)
        val localAddr = InetSocketAddress("127.0.0.1", 55555)

        val config = api.configNew(QUICHE_PROTOCOL_VERSION)
        val alpn = encodeAlpnList(listOf("test"), bufferFactory)
        api.configSetApplicationProtos(config, alpn.nativeMemoryAccess!!.nativeAddress, alpn.remaining())
        api.configSetMaxIdleTimeout(config, 1000L) // 1s idle
        alpn.freeNativeMemory()

        val serverName = "localhost"
        val serverNameBuf = bufferFactory.allocate(serverName.length + 1)
        serverNameBuf.writeString(serverName, Charset.UTF8)
        serverNameBuf.writeByte(0)
        serverNameBuf.resetForRead()
        val scid = generateScid(bufferFactory, Random(42))
        val connectPeer = peerAddr.toNativeSockAddr(bufferFactory)
        val connectLocal = localAddr.toNativeSockAddr(bufferFactory)

        // Pin quiche's clock BEFORE connect so the connection's internal "start" instant is virtual t0.
        val t0 = 100_000_000_000L // 100s from libquiche's fixed anchor (absolute value is irrelevant)
        api.setThreadVirtualTimeNanos(t0)
        val conn =
            api.connect(
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
        api.configFree(config)
        serverNameBuf.freeNativeMemory()
        scid.freeNativeMemory()
        connectPeer.free()
        connectLocal.free()

        val sendInfo = api.sendInfoNew()
        val out = bufferFactory.allocate(1350)
        try {
            // Flush the Initial (ClientHello) at virtual t0 → quiche arms its loss/idle timers off t0.
            val sent = api.connSend(conn, out.nativeMemoryAccess!!.nativeAddress, 1350, sendInfo)
            assertTrue(sent > 0, "expected quiche to flush an Initial packet, got $sent")

            val d0 = api.connTimeout(conn)
            assertNotNull(d0, "quiche should have an armed timer after the first send")
            assertTrue(d0 > Duration.ZERO, "the timer should be in the future at t0, was $d0")
            assertFalse(api.connIsTimedOut(conn), "must not be timed out at t0")

            // Advance quiche's clock halfway to that deadline. If the clock is caller-driven, the
            // reported time-to-timer shrinks by ~half; on an unpatched libquiche it would not move.
            api.setThreadVirtualTimeNanos(t0 + d0.inWholeNanoseconds / 2)
            val d1 = api.connTimeout(conn)
            assertNotNull(d1)
            assertTrue(
                d1 < d0,
                "connTimeout must shrink as virtual time advances (d0=$d0 d1=$d1) — quiche's clock is not caller-driven",
            )

            // Advance well past the idle deadline: the timer is due (zero remaining) and driving
            // on_timeout makes quiche idle-time-out — a full recovery/idle event on virtual time alone.
            api.setThreadVirtualTimeNanos(t0 + d0.inWholeNanoseconds + 30_000_000_000L)
            assertEquals(Duration.ZERO, api.connTimeout(conn), "past the deadline → zero time-to-timer")
            api.connOnTimeout(conn)
            assertTrue(
                api.connIsTimedOut(conn),
                "quiche must report the idle timeout driven purely by the injected virtual clock (no real sleep)",
            )
        } finally {
            api.clearThreadVirtualTime()
            out.freeNativeMemory()
            api.sendInfoFree(sendInfo)
            api.connFree(conn)
        }
    }
}
