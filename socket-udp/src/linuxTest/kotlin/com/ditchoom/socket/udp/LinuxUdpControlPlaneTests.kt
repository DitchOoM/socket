package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Linux row of the §7.1 control-plane matrix, asserted against real io_uring sockets: capability
 * advertisement plus positive read-side roundtrips (ECN / TTL / IP_PKTINFO destination address). Kept
 * out of the shared `nativeTest` suite because Apple advertises a different (managed) ceiling —
 * capabilities are platform-specific by design (consumers query, never assume).
 */
@OptIn(ExperimentalDatagramApi::class)
class LinuxUdpControlPlaneTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bind(): DatagramChannel = UdpSocket.bind("127.0.0.1", 0).also { opened.add(it) }

    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buf = PlatformBuffer.allocateNative(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private suspend fun DatagramChannel.recvDatagram() = assertIs<DatagramReadResult.Received>(withTimeout(5_000) { receive() }).datagram

    private fun udpTest(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    @Test
    fun capabilitiesMatchTheRichLinuxCeiling() =
        udpTest {
            val caps = bind().capabilities
            // Full send + receive control plane on Linux native.
            assertTrue(caps.ecnSend)
            assertTrue(caps.ecnReceive)
            assertTrue(caps.dscpSend)
            assertTrue(caps.dontFragment)
            assertTrue(caps.hopLimitSend)
            assertTrue(caps.hopLimitReceive)
            assertTrue(caps.localAddressReceive)
            // Send-side IP_PKTINFO (fromLocal) and multicast are the only absent capabilities.
            assertFalse(caps.sourceAddressSelect)
            assertFalse(caps.multicast)
        }

    @Test
    fun ecnCodepointRoundTrips() =
        udpTest {
            val a = bind()
            val b = bind()
            // b stamps ECT(0) via IP_TOS; loopback preserves the TOS octet, and a's IP_RECVTOS reports it.
            b.send(payload("ecn"), to = a.localAddress!!, options = DatagramSendOptions(ecn = Ecn.Ect0))
            assertEquals(Ecn.Ect0, a.recvDatagram().ecn)
        }

    @Test
    fun hopLimitRoundTrips() =
        udpTest {
            val a = bind()
            val b = bind()
            // Loopback does not decrement TTL, so the received hop limit equals what b set.
            b.send(payload("ttl"), to = a.localAddress!!, options = DatagramSendOptions(hopLimit = 7))
            assertEquals(7, a.recvDatagram().hopLimit)
        }

    @Test
    fun localAddressIsReportedViaPktinfo() =
        udpTest {
            val a = bind()
            val b = bind()
            b.send(payload("dst"), to = a.localAddress!!)
            val d = a.recvDatagram()
            // IP_PKTINFO reports the datagram's destination IP — the loopback address a is bound to.
            assertEquals("127.0.0.1", d.localAddress?.host)
        }
}
