package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PinAnswer
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheClockPin
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.SocketRefs
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.RecordingSink
import com.ditchoom.socket.quic.core.testing.RecordingWork
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.testing.loopOver
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testConn
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.QuicheTime
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun anIdlePassReadsNoClockForItsCounters() {
        val reads = readsInIdlePass(connections = 4)
        assertEquals(0, reads, "an idle pass takes its time from its caller; its counters read the clock $reads times")
    }

    @Test
    fun aStallInOneConnectionsTurnIsInTheTimeLibquicheReadsForTheNextConnectionsTurnInTheSamePass() {
        // A loaded host can stall inside a pass. Each turn reads the clock once and pins libquiche to it, so the next
        // connection's send times and round trips include the stall, as they did when libquiche read its own clock.
        val clock = VirtualClock(Nanos(PASS_AT), ClockBase.Libquiche)
        val pin = RecordingPin()
        val loop = loopOver(FakePoller(), clock, time = QuicheTime.Pinned(pin))
        val api = StallingApi(clock)
        val channels = ArrayList<DatagramChannel>()
        try {
            repeat(2) { attach(loop, api, clock, channels) }
            loop.pass(Nanos(PASS_AT))
            assertEquals(PASS_AT, pin.pins.first(), "the pass pinned libquiche at its own time")
            assertTrue(api.sends > 0, "the turns asked quiche for output")
            assertTrue(
                pin.pins.any { it >= PASS_AT + STALL },
                "the turn after a ${STALL / 1_000} us stall pinned libquiche at or past it: ${pin.pins}",
            )
        } finally {
            channels.forEach { it.close() }
        }
    }

    /** A quiche whose every send takes [STALL] of the clock, as a starved host's does. */
    private class StallingApi(
        private val clock: VirtualClock,
        private val stub: StubQuicheApi = StubQuicheApi(),
    ) : QuicheApi by stub {
        var sends = 0

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            sends++
            clock.at = clock.at + STALL
            return stub.connSend(conn, buf, bufLen, sendInfo)
        }
    }

    private class RecordingPin : QuicheClockPin {
        val pins = ArrayList<Long>()

        override fun pinMonotonic(nanos: Long): PinAnswer {
            pins += nanos
            return PinAnswer.Pinned
        }

        override fun unpin() = Unit
    }

    private fun attach(
        loop: Loop,
        api: QuicheApi,
        clock: LoopClock,
        channels: MutableList<DatagramChannel>,
    ) {
        val io = FakeIo()
        val socket = SendPort(io, loop.port, CallerLanes.None, RecordingWork(), loop.counts, loop.callerCounts)
        val conn =
            testConn(api, CoreTestRules, SendTarget.ConnectedPeer, TraceCapture.Off, loop, socket, clock, RecordingSink(), ConnCounts())
        val channel = DatagramChannel.open().also { channels.add(it) }
        val batch = OwnBatch(SendBatch(nativeBytes(MAX_DATAGRAMS * DATAGRAM), BatchLimits(MAX_DATAGRAMS, DATAGRAM)))
        loop.attach(Endpoint(SocketRefs(SocketRef(channel), SocketRef(channel)), io, socket, Route.Fixed(conn, batch), Runnable { }))
        conn.start()
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
                        SocketRefs(SocketRef(channel), SocketRef(channel)),
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
        const val PASS_AT = 1_000_000_000L
        const val STALL = 5_000_000L
    }
}
