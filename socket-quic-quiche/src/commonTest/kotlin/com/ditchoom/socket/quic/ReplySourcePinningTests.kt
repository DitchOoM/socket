@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a server channel is chosen for pinning replies, on the parts every platform shares. */
class ReplySourcePinningTests {
    private val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())

    /** A socket bound to one address leaves from it; there is nothing to name, and nothing replaces it. */
    @Test
    fun aSocketBoundToOneAddressIsBoundAddress() =
        runTest {
            val served = QuicPortBinding.Own(port = 0, host = "127.0.0.1").openServerChannel(pool, refuseToReplace)
            try {
                assertEquals(ReplySourcePinning.BoundAddress, served.pinning)
            } finally {
                served.channel.close()
            }
        }

    /** A shared channel is its owner's: described, never replaced, whatever it can do. */
    @Test
    fun aSharedChannelIsDescribedNotReplaced() =
        runTest {
            val owned = QuicPortBinding.Own(port = 0, host = "127.0.0.1").bindServerSocket(pool)
            try {
                val served = QuicPortBinding.Shared(owned).openServerChannel(pool, refuseToReplace)
                assertEquals(ReplySourcePinning.BoundAddress, served.pinning)
                assertTrue(served.channel === owned)
            } finally {
                owned.close()
            }
        }

    @Test
    fun theUnspecifiedAddressIsRecognisedHoweverItIsSpelled() {
        for (host in listOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0", "::%0")) {
            assertTrue(SocketAddress.ofLiteral(host.substringBefore('%'), 1).isUnspecified(), host)
        }
        for (host in listOf("127.0.0.1", "::1", "::ffff:7f00:1")) {
            assertFalse(SocketAddress.ofLiteral(host, 1).isUnspecified(), host)
        }
    }

    private val refuseToReplace = UnpinnedWildcard { _, _, _ -> error("only a wildcard that cannot pin is replaced") }
}
