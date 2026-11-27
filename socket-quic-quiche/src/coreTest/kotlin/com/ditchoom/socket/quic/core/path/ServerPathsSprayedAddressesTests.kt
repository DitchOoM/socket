package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The source addresses a peer sends from are followed up to a bound per connection: a peer spraying addresses at one
 * connection holds at most [MOST] receive infos, the datagrams of addresses past the bound are refused, and an address
 * whose validation is in flight is never evicted to make room.
 */
class ServerPathsSprayedAddressesTests {
    /** The receive infos made and freed through it; a connection handle with nothing behind it has no ids left to ask for. */
    private class CountingApi(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        var made = 0
            private set
        val freed = ArrayList<QuicheRecvInfo>()

        fun live(): Int = made - freed.size

        override fun recvInfoNew(
            fromAddr: Long,
            fromAddrLen: Int,
            toAddr: Long,
            toAddrLen: Int,
        ): QuicheRecvInfo {
            made++
            return real.recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen)
        }

        override fun recvInfoFree(info: QuicheRecvInfo) {
            freed += info
            real.recvInfoFree(info)
        }

        override fun connScidsLeft(conn: QuicheConn): Long = 0L
    }

    private val factory = BufferFactory.network()
    private val server = PathKey(4, 4433, 0L, LOOPBACK)

    private fun key(port: Int) = PathKey(4, port, 0L, LOOPBACK)

    /** A server connection whose first peer address is [FIRST_PORT], and what it frees. */
    private inline fun withPaths(body: (ServerPaths, CountingApi) -> Unit) {
        val api = CountingApi(platformQuicheApi())
        val q = QuicheConnRef(api, QuicheConn(1L))
        val peer = key(FIRST_PORT).encodeToNative(factory)
        val local = server.encodeToNative(factory)
        val target = SendTarget.ServerReply(key(FIRST_PORT), ReplySource.Recorded(server))
        val first = SinglePath(q, SockAddr(peer.address, peer.length), SockAddr(local.address, local.length), target)
        val paths =
            ServerPaths(q, first, key(FIRST_PORT), server, factory, IdIssuer(factory), IdsWanted(), IdsRetired()) { info, _ ->
                api.recvInfoFree(info)
            }
        try {
            body(paths, api)
        } finally {
            paths.made { info, _ -> api.recvInfoFree(info) }
            api.sendInfoFree(paths.sendInfo)
            peer.free()
            local.free()
        }
    }

    @Test
    fun addressesSprayedAtOneConnectionAreBoundedAndTheRestRefused() =
        withPaths { paths, api ->
            val answers = (1..SPRAYED).map { paths.recvInfo(key(FIRST_PORT + it)) }
            val followed = answers.count { it is RecvFrom.Info }
            val refused = answers.count { it === RecvFrom.TooManyAddresses }

            assertEquals(MOST, followed, "the connection followed $MOST of the sprayed addresses: the bound, the first one's room reused")
            assertEquals(SPRAYED - followed, refused, "every address past the bound was refused")
            assertEquals(MOST, api.live(), "no more than $MOST receive infos are live")
            assertEquals(1, api.freed.size, "one was freed to make room: the first, whose validation had settled")
        }

    @Test
    fun anAddressWhoseValidationIsInFlightOutlivesTheBoundAndKeepsItsInfo() =
        withPaths { paths, api ->
            val sprayed = (1..SPRAYED).map { key(FIRST_PORT + it) }
            val kept = sprayed.map { paths.recvInfo(it) }
            val firstAfter = kept.filterIsInstance<RecvFrom.Info>().first()

            // A datagram from an address still being validated is answered with the info it had, never a rebuilt one.
            assertEquals(firstAfter.recvInfo, assertIs<RecvFrom.Info>(paths.recvInfo(sprayed.first())).recvInfo)
            assertEquals(MOST, api.live(), "the repeat made no receive info")
        }

    private companion object {
        const val FIRST_PORT = 50_000
        const val SPRAYED = 100
        const val MOST = 8
        const val LOOPBACK = 0x7F000001L
    }
}
