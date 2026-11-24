package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSocketFault
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A serve on the dual-stack wildcard `::` owns both halves of its port. On Darwin a dual-stack bind succeeds on a port
 * whose IPv4 half an `AF_INET` wildcard socket already holds, and that socket, not the server, then receives every
 * IPv4 datagram (#450, PR #535): the serve must answer that the port is taken instead of serving deaf to IPv4.
 */
class DualStackWildcardBindTests {
    @Test
    fun aServeOnThePortOfAnIpv4WildcardHolderIsRefusedNotDeaf(): Unit =
        runBlocking {
            skipOnMissingNativeLib(DualStackWildcardBindTests::class) {
                DatagramChannel.open(StandardProtocolFamily.INET).use { holder ->
                    holder.bind(InetSocketAddress("0.0.0.0", 0))
                    val port = (holder.localAddress as InetSocketAddress).port
                    val served = serveQuicOnCore(port, "::", platformTlsConfig(), OPTIONS) { "served" }
                    val failed = assertIs<QuicServe.Failed>(served, "a serve on [::]:$port while 0.0.0.0:$port is held")
                    assertIs<QuicSocketFault.OpenRefused>(failed.fault)
                }
            }
        }

    private companion object {
        val OPTIONS = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
    }
}
