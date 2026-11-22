package com.ditchoom.socket.quic.core.edge

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.socket.AndroidLocalNetworkAccess
import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.androidLocalNetworkAccess
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * On Android 17+ an app without `ACCESS_LOCAL_NETWORK` connecting to the local network through the core fails at once,
 * naming the permission. This test app declares the permission and nothing grants it at runtime, so on API 37+ it is
 * denied; a device that granted it skips.
 */
@RunWith(AndroidJUnit4::class)
class CoreLocalNetworkDeniedTests {
    @Test
    fun aCoreConnectToTheLocalNetworkFailsAtOnceNamingThePermission() {
        assumeTrue("local-network protection starts at API 37", Build.VERSION.SDK_INT >= AndroidLocalNetworkAccess.ENFORCED_FROM_API)
        assumeTrue(
            "ACCESS_LOCAL_NETWORK is granted on this device",
            NetworkMonitor.androidLocalNetworkAccess() == AndroidLocalNetworkAccess.Denied,
        )
        val neighbours = onLinkNeighbours()
        assumeTrue("no IPv4 network with an on-link neighbour address", neighbours.isNotEmpty())

        val (failure, took) =
            measureTimedValue {
                assertFailsWith<QuicCloseException> { runBlocking { connectCore(PORT, host = neighbours.first()) } }
            }

        val end = assertIs<QuicConnectionEnd.AccessDenied>(failure.end)
        assertSame(QuicAccessDenial.AndroidPermissionNotHeld, end.denial)
        assertEquals(NetworkAccess.LocalNetwork, end.denial.access)
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

    private companion object {
        const val PORT = 4433
    }
}
