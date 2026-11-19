package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.RecordingSink
import com.ditchoom.socket.quic.core.testing.RecordingWork
import com.ditchoom.socket.quic.core.testing.loopOver
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testConn
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pass reads the clock a fixed number of times, however many connections share the loop. A clock read is a system call
 * on some kernels, and on a guest whose clocksource left the TSC a trap to the host: reads per connection per pass made
 * a herd of 1,024 connections spend most of its CPU in the kernel on the Android 9 and 10 emulators.
 */
class LoopClockReadsTests {
    @Test
    fun anIdlePassReadsTheClockAsOftenWithSixtyFourConnectionsAsWithOne() {
        val one = readsInIdlePass(connections = 1)
        val many = readsInIdlePass(connections = 64)
        assertEquals(one, many, "clock reads in one idle pass: $one with 1 connection, $many with 64")
    }

    private class CountingClock : LoopClock {
        var reads = 0

        override fun now(): Nanos {
            reads++
            return Nanos(0L)
        }
    }

    private fun readsInIdlePass(connections: Int): Int {
        val clock = CountingClock()
        val loop = loopOver(FakePoller(), clock)
        val channels = ArrayList<DatagramChannel>()
        try {
            repeat(connections) {
                val io = FakeIo()
                val socket = SendPort(io, loop.port, CallerLanes.None, RecordingWork(), loop.counts, loop.callerCounts)
                val conn =
                    testConn(
                        StubQuicheApi(),
                        CoreTestRules,
                        SendTarget.ConnectedPeer,
                        TraceCapture.Off,
                        loop,
                        socket,
                        clock,
                        RecordingSink(),
                        ConnCounts(),
                    )
                val channel = DatagramChannel.open().also { channels.add(it) }
                val endpoint =
                    Endpoint(
                        SocketRef(channel),
                        io,
                        socket,
                        Route.Fixed(conn, OwnBatch(SendBatch(nativeBytes(MAX_DATAGRAMS * DATAGRAM), BatchLimits(MAX_DATAGRAMS, DATAGRAM)))),
                        Runnable { },
                    )
                loop.attach(endpoint)
                conn.start()
            }
            // The first pass settles what starting the connections asked for; the second has nothing to do.
            loop.pass(Nanos(0L))
            clock.reads = 0
            loop.pass(Nanos(0L))
            return clock.reads
        } finally {
            channels.forEach { it.close() }
        }
    }

    private companion object {
        const val MAX_DATAGRAMS = 4
        const val DATAGRAM = 1500
    }
}
