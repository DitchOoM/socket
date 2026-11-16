package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.path.SockAddr
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Handshake] and [Release]: the two components that act once per connection. */
class HandshakeAndReleaseTests {
    @Test
    fun theHandshakeReportsEstablishmentOnceWithItsDuration() {
        val api = StubQuicheApi().apply { established = false }
        val handshake = Handshake(QuicheConnRef(api, QuicheConn(1L)), startedNanos = 100L, alpn = nativeBytes(255))
        assertEquals(HandshakeStep.Handshaking, handshake.step())
        api.established = true
        assertEquals(HandshakeStep.JustEstablished, handshake.step())
        assertEquals(HandshakeStep.Established, handshake.step())
        assertEquals(400L, handshake.report(Nanos(500L)).handshakeNanos)
    }

    @Test
    fun aReleaseFreesQuichesMemoryOnceAndOnlyAfterItWasAsked() {
        val freed = ArrayList<String>()
        val api =
            object : com.ditchoom.socket.quic.QuicheApi by StubQuicheApi() {
                override fun connFree(conn: QuicheConn) {
                    freed += "conn"
                }

                override fun recvInfoFree(info: QuicheRecvInfo) {
                    freed += "recv"
                }

                override fun sendInfoFree(info: QuicheSendInfo) {
                    freed += "send"
                }
            }
        val q = QuicheConnRef(api, QuicheConn(1L))
        val path = SinglePath(q, SockAddr(0L, 0), SockAddr(0L, 0), SendTarget.ConnectedPeer)
        val release = Release(q)
        release.free(path)
        assertEquals(emptyList(), freed, "nothing asked for a release")
        var then = 0
        release.request { then++ }
        assertEquals(ReleaseAsk.Yes, release.asked())
        release.free(path)
        release.free(path)
        assertEquals(listOf("conn", "recv", "send"), freed)
        assertEquals(1, then)
    }
}
