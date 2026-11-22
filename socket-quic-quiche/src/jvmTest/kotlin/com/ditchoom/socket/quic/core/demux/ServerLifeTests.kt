package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.time.Nanos
import kotlin.test.Test
import kotlin.test.assertEquals

/** A server's drain bound is its close's limit and a second, held at the end of time rather than wrapping. */
class ServerLifeTests {
    @Test
    fun aCloseWithNoLimitNeverReachesTheDrainBound() {
        val rig = ServerRig()
        val life = ServerLife(CloseChoice.Graceful(Long.MAX_VALUE, DrainUntil.PeerDone))
        life.begin(Nanos(1_000L), rig.loop.port)
        assertEquals(ServerStep.Serving, life.step(Nanos(Long.MAX_VALUE - 1), 1), "the bound did not wrap into the past")
    }

    @Test
    fun theDrainBoundIsTheLimitAndASecond() {
        val rig = ServerRig()
        val life = ServerLife(CloseChoice.Graceful(LIMIT, DrainUntil.Acknowledged))
        life.begin(Nanos(0L), rig.loop.port)
        assertEquals(ServerStep.Serving, life.step(Nanos(LIMIT + SECOND - 1), 1))
        assertEquals(ServerStep.FinishAtBound, life.step(Nanos(LIMIT + SECOND), 1))
    }

    @Test
    fun aTimePlusADelayHoldsAtTheEndsOfTimeInsteadOfWrapping() {
        assertEquals(Nanos(Long.MAX_VALUE), Nanos(5L) + Long.MAX_VALUE)
        assertEquals(Nanos(Long.MIN_VALUE), Nanos(-5L) + Long.MIN_VALUE)
        assertEquals(Nanos(7L), Nanos(5L) + 2L)
        assertEquals(Nanos(3L), Nanos(5L) + -2L)
    }

    private companion object {
        const val LIMIT = 10_000_000_000L
        const val SECOND = 1_000_000_000L
    }
}
