package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A server connection's linger ends at its time and the connection leaves its routes, whoever holds the connection when
 * that time comes. The test thread is the loop; virtual time.
 */
class ServerLingerTests {
    @Test
    fun aLingerThatEndsWhileACallerIsInsideStillEndsAndUnroutesItsConnection() {
        val api =
            StubQuicheApi().apply {
                emitOneDatagramOnClose = true
                connTimeout = DRAIN.nanoseconds
            }
        val rig = ServerRig(api, lanes = 2)
        rig.io.arrive(initial(Random(5).nextBytes(8)), peer(1))
        rig.pass(0)
        val conn = rig.conns.single()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The caller that closes the connection is held inside it while it sends the close in place.
        rig.io.onSend = {
            if (Thread.currentThread().name == CALLER) {
                inside.countDown()
                release.await()
            }
        }
        val caller = Thread { conn.submit(Closes(), CallerPlace.MayEnter) }
        caller.name = CALLER
        caller.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the caller closed the connection and is sending its close")
        assertIs<Life.Lingering>(conn.life(), "the close is out: the connection lingers")

        // The linger's time comes while the caller is still inside.
        rig.pass(DRAIN)
        rig.pass(DRAIN)
        release.countDown()
        caller.join(5_000)
        rig.pass(DRAIN + 1)
        rig.pass(DRAIN + 2)

        assertIs<Life.Ended>(conn.life(), "the linger ended: ${routedEvidence(rig.demux)}")
        assertEquals(0, rig.demux.connections, "the ended connection left its routes")
    }

    /** Inside: closes the connection at once. */
    private class Closes : ConnWork() {
        override fun run(inside: Inside) = inside.close(QuicError.ApplicationError(1))

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val CALLER = "test-caller"
        const val DRAIN = 180_000_000L
    }
}
