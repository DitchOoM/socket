package com.ditchoom.socket.http3

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.socket.AndroidLocalNetworkAccess
import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.androidLocalNetworkAccess
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * This module's test app targets the compile SDK and does not declare `ACCESS_LOCAL_NETWORK`, so on
 * Android 17+ it is an app the system denies the local network to. The test runs only there.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLocalNetworkDeniedTest {
    @Test
    fun aQuicConnectToTheLocalNetworkFailsAtOnceNamingThePermission() {
        assumeTrue("local-network protection starts at API 37", Build.VERSION.SDK_INT >= AndroidLocalNetworkAccess.ENFORCED_FROM_API)
        assertEquals(AndroidLocalNetworkAccess.Denied, NetworkMonitor.androidLocalNetworkAccess())
        val neighbours = onLinkNeighbours()
        assumeTrue("no IPv4 network with an on-link neighbour address", neighbours.isNotEmpty())

        val (failure, took) =
            measureTimedValue {
                assertFailsWith<SocketConnectionException> {
                    runBlocking {
                        withQuicConnection(neighbours.first(), 4433, QuicOptions(alpnProtocols = listOf("test")), timeout = 10.seconds) {}
                    }
                }
            }

        assertEquals(ConnectionFailureReason.AccessDenied(NetworkAccess.LocalNetwork), failure.reason)
        assertTrue(AndroidLocalNetworkAccess.PERMISSION in failure.message.orEmpty(), "message was: ${failure.message}")
        assertTrue(took < 2.seconds, "the denial took $took, so the connect waited instead of failing on the refused send")
    }

    /** One other address in each attached IPv4 subnet: local network by any definition of it. */
    private fun onLinkNeighbours(): List<String> =
        NetworkInterface
            .getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.networkPrefixLength in 8..30 }
            .map { own ->
                val bytes = own.address.address
                bytes[3] = (bytes[3].toInt() xor 1).toByte()
                InetAddress.getByAddress(bytes).hostAddress.orEmpty()
            }.toList()
}
