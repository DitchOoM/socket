package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Linux row of the §7.1 control-plane matrix, asserted against a real io_uring socket. Kept out of
 * the shared `nativeTest` suite because Apple advertises a different (managed) ceiling — capabilities
 * are platform-specific by design (consumers query, never assume).
 *
 * Phase 3a: send-side ECN/DSCP (socket-wide `IP_TOS`/`IPV6_TCLASS`) are present; DF and the read-side
 * cmsg plane (ECN/TTL/PKTINFO) are advertised **absent** until Phase 3b wires the `recvmsg` control
 * plane — so consumers degrade correctly (§7.2), never silently.
 */
@OptIn(ExperimentalDatagramApi::class)
class LinuxUdpCapabilitiesTests {
    @Test
    fun capabilitiesMatchThePhase3aLinuxCeiling() =
        runBlocking {
            withTimeout(10_000) {
                val channel = UdpSocket.bind("127.0.0.1", 0)
                try {
                    val caps = channel.capabilities
                    // Send-side ECN + DSCP via IP_TOS / IPV6_TCLASS.
                    assertTrue(caps.ecnSend, "IP_TOS ECN send should be supported on Linux")
                    assertTrue(caps.dscpSend, "IP_TOS DSCP send should be supported on Linux")
                    // Deferred to Phase 3b (recvmsg cmsg plane + IP_MTU_DISCOVER) — advertised absent now.
                    assertFalse(caps.ecnReceive)
                    assertFalse(caps.hopLimitReceive)
                    assertFalse(caps.localAddressReceive)
                    assertFalse(caps.sourceAddressSelect)
                    assertFalse(caps.dontFragment)
                    assertFalse(caps.hopLimitSend)
                    // Multicast is design-for/defer (§10.3).
                    assertFalse(caps.multicast)
                } finally {
                    channel.close()
                }
            }
        }
}
