package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Apple server socket pins each reply per datagram, so a wildcard bind is served as is. */
class AppleReplySourcePinningTests {
    @Test
    fun aWildcardServerSocketPinsPerDatagram() =
        runTest {
            val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())
            val served =
                QuicPortBinding.Own(port = 0).openServerChannel(pool) { _, _, _ ->
                    error("an Apple wildcard socket pins its replies itself and is never replaced")
                }
            try {
                assertEquals(ReplySourcePinning.PerDatagram, served.pinning)
            } finally {
                served.channel.close()
            }
        }
}
