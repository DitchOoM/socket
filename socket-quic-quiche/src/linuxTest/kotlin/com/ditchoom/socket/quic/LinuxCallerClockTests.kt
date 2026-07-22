@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    com.ditchoom.buffer.flow.ExperimentalDatagramApi::class,
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
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.linuxSockAddrLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.runBlocking
import platform.posix.sockaddr
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * linuxX64 proof that the caller-clock source patch (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §6.1) actually
 * makes libquiche's **internal** clock caller-driven — the crux the patch exists for. Talks to the real
 * cinterop [CinteropQuicheApi] against the self-built, patched libquiche (with the current self-built
 * BoringSSL). No live peer, no socket I/O, no wall-clock waiting: a real quiche client `Connection` is
 * created, its timers armed by one `send()`, and then the connection's own clock is advanced purely via
 * [QuicheApi.setThreadVirtualTimeNanos].
 *
 * The discriminating assertion is [connTimeout] **shrinking** as virtual time advances: on an *un*patched
 * libquiche the time-to-next-timer is computed off the real `Instant::now()` (which barely moves inside a
 * test), so it would stay ~constant; here it tracks the injected virtual clock exactly. The idle-timeout
 * assertion then shows a full loss/idle event firing on virtual time alone.
 */
class LinuxCallerClockTests {
    private val bufferFactory = BufferFactory.deterministic()

    @Test
    fun virtualTimeDrivesQuicheInternalClock() =
        runBlocking {
            val api = CinteropQuicheApi
            val codec = SocketAddressCodec(linuxSockAddrLayout)
            // Numeric literals → no DNS; no socket is opened, the sockaddrs only seed quiche's path state.
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

            // Pin quiche's clock BEFORE connect so the connection's internal "start" instant is virtual t0.
            val t0 = 100_000_000_000L // 100s from libquiche's fixed anchor (absolute value is irrelevant)
            api.setThreadVirtualTimeNanos(t0)
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
            try {
                // Flush the Initial (ClientHello) at virtual t0 → quiche arms its loss/idle timers off t0.
                val sent =
                    memScoped {
                        val out = allocArray<ByteVar>(1350)
                        api.connSend(conn, out.rawValue.toLong(), 1350, sendInfo)
                    }
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
                api.sendInfoFree(sendInfo)
                api.connFree(conn)
                peerSA.free()
                localSA.free()
                scid.freeNativeMemory()
            }
        }
}
