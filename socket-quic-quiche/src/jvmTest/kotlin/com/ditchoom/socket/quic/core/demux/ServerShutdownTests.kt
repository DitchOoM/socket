package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.BoundedIo.Companion.CLOSED
import com.ditchoom.socket.quic.core.demux.BoundedIo.Companion.SENT
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.life.ConnEnd
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A server's shutdown while a caller's thread is inside one of its connections: the server stops, and its socket
 * closes, only after that connection's CONNECTION_CLOSE went out. The test thread is the loop; virtual time.
 */
class ServerShutdownTests {
    @Test
    fun theSocketClosesOnlyAfterTheCloseOfAConnectionACallerHeldWentOut() {
        val (rig, conn) = serverWithOnePeer()
        val holder = HoldWork()
        val caller = holdAsCaller(conn, holder)

        rig.demux.beginShutdown()
        rig.pass()
        rig.pass()
        assertNotEquals(ServerPhase.Stopped, rig.demux.phase(), "a caller is inside: its connection's close has not gone out")
        assertEquals(emptyList(), rig.io.events, "nothing was sent and the socket is open")

        holder.release.countDown()
        caller.join(5_000)
        rig.pass()
        assertEquals(ServerPhase.Stopped, rig.demux.phase())
        assertEquals(listOf(SENT, CLOSED), rig.io.events.toList(), "the CONNECTION_CLOSE went out, then the socket closed")
        assertEquals<List<ConnEnd>>(listOf(ConnEnd.ServerClosed), rig.sinks.single().ended)
    }

    /** A server with one accepted connection, whose quiche writes one datagram once it is closed, and a caller lane. */
    private fun serverWithOnePeer(): Pair<ServerRig, Conn> {
        val api = StubQuicheApi().apply { emitOneDatagramOnClose = true }
        val rig = ServerRig(api, lanes = 2)
        rig.io.arrive(initial(Random(11).nextBytes(8)), peer(1))
        rig.pass()
        assertEquals(1L, rig.counts.accepted)
        return rig to rig.conns.single()
    }

    /** Makes a thread enter [conn] as a caller and block in [holder] until released. */
    private fun holdAsCaller(
        conn: Conn,
        holder: HoldWork,
    ): Thread {
        val thread = Thread { conn.submit(holder, CallerPlace.MayEnter) }
        thread.name = "test-caller"
        thread.start()
        assertTrue(holder.entered.await(5, TimeUnit.SECONDS), "the caller entered the connection")
        return thread
    }

    /** Work that blocks the thread inside the connection until released. */
    private class HoldWork : ConnWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun run(inside: Inside) {
            entered.countDown()
            release.await()
        }

        override fun gone(end: ConnEnd) = Unit
    }
}
