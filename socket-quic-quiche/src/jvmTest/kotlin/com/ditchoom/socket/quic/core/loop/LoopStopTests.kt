package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.ReadyTokens
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WakeCause
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.EchoQuiche
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SocketClosed
import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A loop's stop releases each thing it owns once: run again after it stopped, or failing partway through its stop,
 * it never reaches a released poller, socket or memory a second time.
 */
class LoopStopTests {
    @Test
    fun aStoppedLoopRunAgainReleasesNothingTwice() {
        val poller = CountingPoller()
        var freed = 0
        val loop = loop(poller, Runnable { freed++ })
        var thens = 0
        loop.close(Runnable { thens++ })
        loop.run()
        loop.close(Runnable { thens++ })
        loop.run()
        assertEquals(1, poller.closes, "the poller is closed once")
        assertEquals(1, freed, "the loop's memory is freed once")
        assertEquals(2, thens, "each close's continuation runs once")
    }

    @Test
    fun aPollerWhoseCloseThrowsIsClosedOnceAndTheRestOfTheStopStillRuns() {
        val poller = CountingPoller(closeThrows = true)
        var freed = 0
        val loop = loop(poller, Runnable { freed++ })
        var thens = 0
        loop.close(Runnable { thens++ })
        loop.run()
        assertEquals(1, poller.closes)
        assertEquals(1, freed, "the memory is freed although the poller's close threw")
        assertEquals(1, thens, "the close's continuation runs although the stop failed")
    }

    @Test
    fun aFreeThatThrowsIsNotRunAgainAndThePollerIsNotClosedAgain() {
        val poller = CountingPoller()
        var freed = 0
        val loop =
            loop(
                poller,
                Runnable {
                    freed++
                    throw IllegalStateException("injected")
                },
            )
        loop.close(Runnable { })
        loop.run()
        assertEquals(1, poller.closes)
        assertEquals(1, freed)
    }

    @Test
    fun aSocketWhoseCloseThrowsIsClosedOnceAndTheOtherReleasesStillRun() {
        val io = ThrowingCloseIo()
        val poller = CountingPoller()
        val rig = ConnRig(EchoQuiche(), io = io, poller = poller)
        rig.loop.close(Runnable { })
        rig.loop.run()
        assertEquals(1, io.closes, "the socket is closed once")
        assertEquals(1, poller.closes)
    }

    private fun loop(
        poller: Poller,
        freeOnStop: Runnable,
    ): Loop =
        Loop(
            id = 1,
            name = "stop-test",
            poller = poller,
            clock = VirtualClock(),
            receive = ReceiveBatch(FixedPool(1) { ReceiveBuffer(it, nativeBytes(1500)) }, BatchLimits(1, 1500)),
            freeOnStop = freeOnStop,
        )
}

/** A poller that counts its closes and, when asked to, throws from the first. */
private class CountingPoller(
    private val closeThrows: Boolean = false,
) : Poller {
    var closes = 0
        private set

    override fun add(
        socket: SocketRef,
        token: Int,
    ) = Unit

    override fun remove(token: Int) = Unit

    override fun interest(
        token: Int,
        write: WriteInterest,
    ) = Unit

    override fun await(
        until: NextDue,
        ready: ReadyTokens,
        now: Nanos,
    ): WakeCause = WakeCause.Woken

    override fun wake() = Unit

    override fun close() {
        closes++
        if (closeThrows) throw IllegalStateException("injected")
    }
}

/** A socket whose close counts and throws. */
private class ThrowingCloseIo(
    private val inner: FakeIo = FakeIo(),
) : DatagramBatchIo by inner {
    var closes = 0
        private set

    override fun close(): SocketClosed {
        closes++
        throw IllegalStateException("injected")
    }
}
