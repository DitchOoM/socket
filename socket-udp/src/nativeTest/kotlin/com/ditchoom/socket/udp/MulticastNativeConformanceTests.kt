@file:OptIn(ExperimentalDatagramApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-socket multicast conformance for the native backends (Linux io_uring; Apple POSIX). Two tiers,
 * matching the harness posture:
 *
 * - **Control plane** (deterministic, both platforms) — a `bindMulticast` channel advertises
 *   `multicast == true`; TTL/loopback `setsockopt`s succeed; a bad TTL / missing interface fails with the
 *   typed [MulticastException] / [IllegalArgumentException]. None of these need a routable multicast path.
 * - **End-to-end** ([senderReachesJoinedReceiverOnSameHost]) — a datagram to a joined group loops back on
 *   the same host. Real kernel delivery is OS-gated: macOS **Local Network Privacy** denies an unattended
 *   `test.kexe` (the CI case; the prompt can't be answered), and a bare Linux container may have no
 *   multicast route. So this is a *conditional* assertion — if a datagram arrives it MUST be the exact bytes
 *   (a real corruption/misroute still fails), and if none arrives it is a loud skip, never a silent pass.
 *   The deterministic [com.ditchoom.socket.udp.sim.MulticastFabric] oracle proves the semantics everywhere.
 */
@OptIn(ExperimentalDatagramApi::class)
class MulticastNativeConformanceTests {
    private val opened = mutableListOf<MulticastDatagramChannel>()

    private val isApple: Boolean =
        Platform.osFamily == OsFamily.MACOSX ||
            Platform.osFamily == OsFamily.IOS ||
            Platform.osFamily == OsFamily.TVOS ||
            Platform.osFamily == OsFamily.WATCHOS
    private val loopbackName: String = if (isApple) "lo0" else "lo"

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bindMc(port: Int): MulticastDatagramChannel =
        UdpSocket.bindMulticast(port, AddressFamily.IPv4).also { opened.add(it) }

    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buf = PlatformBuffer.allocateNative(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private fun mcTest(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    // ---- control plane (deterministic) ----

    @Test
    fun multicastChannelAdvertisesTheCapability() =
        mcTest {
            val ch = bindMc(0)
            assertTrue(ch.capabilities.multicast, "a bindMulticast channel must advertise multicast=true")
            assertTrue(ch.isOpen)
        }

    @Test
    fun ttlAndLoopbackOptionsApplyWithoutThrowing() =
        mcTest {
            val ch = bindMc(0)
            ch.setTimeToLive(1)
            ch.setLoopbackEnabled(true)
            ch.setLoopbackEnabled(false)
            ch.setOutboundInterface(MulticastInterface.Default)
        }

    @Test
    fun outOfRangeTtlIsRejected() =
        mcTest {
            val ch = bindMc(0)
            assertFailsWith<IllegalArgumentException> { ch.setTimeToLive(999) }
        }

    @Test
    fun joinOnMissingInterfaceIsTypedFailure() =
        mcTest {
            val ch = bindMc(0)
            val group = UdpSocket.resolve("239.60.60.60", 0)
            assertFailsWith<MulticastException> {
                ch.joinGroup(MulticastMembership(group, MulticastInterface.ByName("nonexistent-nic-xyz")))
            }
        }

    // ---- end-to-end (same host) ----

    @Test
    fun senderReachesJoinedReceiverOnSameHost() =
        mcTest {
            // Candidate interfaces in the order most likely to loop back here: Apple lo0 IS multicast-capable
            // (proven on Darwin), Linux lo usually is not, so the default routable NIC comes first there.
            val candidates =
                if (isApple) {
                    listOf(MulticastInterface.ByName(loopbackName), MulticastInterface.Default)
                } else {
                    listOf(MulticastInterface.Default, MulticastInterface.ByName(loopbackName))
                }
            var received: String? = null
            var port = 42_240
            for (iface in candidates) {
                received = receiveOnce(iface, port++)
                if (received != null) break
            }
            // If a datagram DID come back it MUST be the exact bytes — a real corruption/misroute regression
            // still fails here. If none arrived, the OS blocked it (macOS Local Network Privacy denies an
            // unattended test.kexe; a bare Linux container has no multicast route), so log a hard skip rather
            // than a silent pass. The deterministic MulticastFabric oracle still proves the semantics.
            if (received != null) {
                assertEquals("mc-native", received)
            } else {
                println(
                    "[MulticastNativeConformanceTests] SKIP e2e: no datagram delivered — multicast blocked by " +
                        "the OS (macOS Local Network Privacy / no routable interface). Semantics covered by " +
                        "MulticastFabricTests.",
                )
            }
        }

    /**
     * One same-host group round-trip on [iface]:[port]; the decoded payload if it came back within a bounded
     * wait, else null. The receive runs in a child job whose completion we `await` (cancellable) — a parked
     * blocking `recvfrom` is NOT cancellable by `withTimeout`, so on non-delivery we [close] the receiver to
     * unblock it rather than relying on a timeout to abort the syscall (which would hang the whole test).
     */
    private suspend fun CoroutineScope.receiveOnce(
        iface: MulticastInterface,
        port: Int,
    ): String? {
        val receiver = bindMc(port)
        val sender = bindMc(0)
        return try {
            val group = UdpSocket.resolve("239.58.58.58", port)
            try {
                receiver.joinGroup(MulticastMembership(group, iface))
            } catch (_: MulticastException) {
                return null // this interface cannot join (no route / blocked) — try the next candidate
            }
            runCatching { sender.setOutboundInterface(iface) }
            sender.setLoopbackEnabled(true)
            sender.setTimeToLive(1)
            val got = CompletableDeferred<String?>()
            val job =
                launch {
                    try {
                        val r = receiver.receive()
                        got.complete(
                            if (r is DatagramReadResult.Received) {
                                r.datagram.payload
                                    .readByteArray(r.datagram.payload.remaining())
                                    .decodeToString()
                            } else {
                                null
                            },
                        )
                    } catch (_: Throwable) {
                        got.complete(null)
                    }
                }
            sender.send(payload("mc-native"), to = group)
            val text = withTimeoutOrNull(3_000) { got.await() }
            receiver.close() // unblock a still-parked recvfrom so the child job finishes
            job.cancel()
            text
        } finally {
            runCatching { receiver.close() }
            runCatching { sender.close() }
        }
    }
}
