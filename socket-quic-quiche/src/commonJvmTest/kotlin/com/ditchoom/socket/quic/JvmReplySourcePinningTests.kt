@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A JVM wildcard socket cannot pin a reply's source (NIO has no ancillary data), so a wildcard bind is
 * served one socket per local address — for either wildcard spelling, not only an absent host.
 */
class JvmReplySourcePinningTests {
    private val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())

    @Test
    fun aWildcardBindIsServedOneSocketPerAddress() =
        runBlocking {
            for (host in listOf(null, "::", "0.0.0.0")) {
                val served = QuicPortBinding.Own(port = 0, host = host).openServerChannel(pool, SocketPerLocalAddress)
                try {
                    assertEquals(ReplySourcePinning.SocketPerAddress, served.pinning, "host=$host")
                    assertTrue(!served.channel.localAddress.isUnspecified(), "host=$host: no member is the wildcard")
                } finally {
                    served.channel.close()
                }
            }
        }

    @Test
    fun theWildcardSocketItselfCannotPin() =
        runBlocking {
            val socket = QuicPortBinding.Own(port = 0).bindServerSocket(pool)
            try {
                assertEquals(ReplySourcePinning.PlatformChooses, socket.replySourcePinning())
            } finally {
                socket.close()
            }
        }
}
