package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.testing.stubClientPaths
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Release]: quiche's memory is freed once, and only after it was asked. */
class ReleaseTests {
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
        val path = stubClientPaths(q)
        val release = Release(q, Qlog.Unbudgeted)
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
